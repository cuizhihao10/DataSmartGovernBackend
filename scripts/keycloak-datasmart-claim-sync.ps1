<#
DataSmart local Keycloak claim synchronization script.

Purpose:
1. Fix an existing PostgreSQL-backed local realm without deleting Docker volumes.
2. Idempotently align datasmart-gateway protocol mappers, especially datasmart_project_ids.
3. Idempotently align local sample users, low-sensitive DataSmart attributes, realm roles and dev passwords.

Safety boundary:
- The script never prints admin tokens, access tokens, refresh tokens, passwords, client secrets or full JWTs.
- datasmart_project_ids is only an IdP-side candidate project set, not the final business authorization.
- gateway must still call permission-admin to materialize trusted project/data-scope authorization.
- The default mode is dry-run; pass -Apply to write Keycloak.
#>

[CmdletBinding()]
param(
    [string]$KeycloakBaseUrl,
    [string]$Realm,
    [string]$AdminRealm,
    [string]$AdminClientId,
    [string]$AdminClientSecret,
    [string]$AdminUsername,
    [string]$AdminPassword,
    [string]$GatewayClientId,
    [string]$LocalUserPassword,
    [switch]$Apply,
    [switch]$SkipPasswordReset,
    [switch]$SkipBruteForceClear
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-Setting {
    param(
        [string]$Value,
        [string]$EnvironmentName,
        [string]$DefaultValue
    )
    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        return $Value
    }
    $fromEnvironment = [Environment]::GetEnvironmentVariable($EnvironmentName)
    if (-not [string]::IsNullOrWhiteSpace($fromEnvironment)) {
        return $fromEnvironment
    }
    return $DefaultValue
}

function Write-Step {
    param([string]$Message)
    Write-Host "[STEP] $Message"
}

function Write-Change {
    param(
        [string]$Action,
        [scriptblock]$Operation
    )
    if ($Apply) {
        & $Operation
        Write-Host "[APPLIED] $Action"
    }
    else {
        Write-Host "[DRY-RUN] $Action"
    }
}

function Get-HttpStatusCode {
    param([object]$ErrorRecord)
    if ($null -ne $ErrorRecord.Exception -and $null -ne $ErrorRecord.Exception.Response) {
        try {
            return [int]$ErrorRecord.Exception.Response.StatusCode
        }
        catch {
            return $null
        }
    }
    return $null
}

function ConvertTo-JsonBody {
    param([object]$Body)
    return ($Body | ConvertTo-Json -Depth 80)
}

function Convert-KeycloakList {
    param([object]$Response)
    if ($null -eq $Response) {
        return @()
    }
    if ($Response -is [System.Array]) {
        return @($Response)
    }
    if (($Response.PSObject.Properties.Name -contains "value") -and ($Response.PSObject.Properties.Name -contains "Count")) {
        return @($Response.value)
    }
    return @($Response)
}

$KeycloakBaseUrl = (Resolve-Setting -Value $KeycloakBaseUrl -EnvironmentName "DATASMART_KEYCLOAK_BASE_URL" -DefaultValue "http://localhost:18080").TrimEnd("/")
$Realm = Resolve-Setting -Value $Realm -EnvironmentName "DATASMART_KEYCLOAK_REALM" -DefaultValue "datasmart"
$AdminRealm = Resolve-Setting -Value $AdminRealm -EnvironmentName "DATASMART_KEYCLOAK_ADMIN_REALM" -DefaultValue "master"
$AdminClientId = Resolve-Setting -Value $AdminClientId -EnvironmentName "DATASMART_KEYCLOAK_ADMIN_CLIENT_ID" -DefaultValue "admin-cli"
$AdminClientSecret = Resolve-Setting -Value $AdminClientSecret -EnvironmentName "DATASMART_KEYCLOAK_ADMIN_CLIENT_SECRET" -DefaultValue ""
$AdminUsername = Resolve-Setting -Value $AdminUsername -EnvironmentName "DATASMART_KEYCLOAK_ADMIN_USERNAME" -DefaultValue "admin"
$AdminPassword = Resolve-Setting -Value $AdminPassword -EnvironmentName "DATASMART_KEYCLOAK_ADMIN_PASSWORD" -DefaultValue "admin"
$GatewayClientId = Resolve-Setting -Value $GatewayClientId -EnvironmentName "DATASMART_GATEWAY_OIDC_REQUIRED_AUDIENCE" -DefaultValue "datasmart-gateway"
$LocalUserPassword = Resolve-Setting -Value $LocalUserPassword -EnvironmentName "DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD" -DefaultValue "DataSmart@123"

function Get-AdminAccessToken {
    <#
    Keycloak Admin API first needs a short-lived admin token from the master realm.
    The token is never printed and is only used in the Authorization header.
    #>
    $tokenUri = "$KeycloakBaseUrl/realms/$AdminRealm/protocol/openid-connect/token"
    $body = @{
        grant_type = "password"
        client_id = $AdminClientId
        username = $AdminUsername
        password = $AdminPassword
    }
    if (-not [string]::IsNullOrWhiteSpace($AdminClientSecret)) {
        $body.client_secret = $AdminClientSecret
    }
    $response = Invoke-RestMethod -Method Post -Uri $tokenUri -ContentType "application/x-www-form-urlencoded" -Body $body
    if ($null -eq $response.access_token) {
        throw "Keycloak Admin API token response missing access_token."
    }
    return $response.access_token
}

$script:AdminHeaders = @{
    Authorization = "Bearer $(Get-AdminAccessToken)"
}

function Invoke-KeycloakAdminRoot {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [switch]$AllowNotFound
    )
    $uri = "$KeycloakBaseUrl/admin$Path"
    $parameters = @{
        Method = $Method
        Uri = $uri
        Headers = $script:AdminHeaders
        ErrorAction = "Stop"
    }
    if ($null -ne $Body) {
        $parameters.Body = ConvertTo-JsonBody -Body $Body
        $parameters.ContentType = "application/json; charset=utf-8"
    }
    try {
        return Invoke-RestMethod @parameters
    }
    catch {
        $statusCode = Get-HttpStatusCode -ErrorRecord $_
        if ($AllowNotFound -and $statusCode -eq 404) {
            return $null
        }
        throw "Keycloak Admin API failed: method=$Method path=$Path status=$statusCode. $($_.Exception.Message)"
    }
}

function Invoke-KeycloakAdmin {
    param(
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [switch]$AllowNotFound
    )
    return Invoke-KeycloakAdminRoot -Method $Method -Path "/realms/$Realm$Path" -Body $Body -AllowNotFound:$AllowNotFound
}

function Set-Property {
    param(
        [object]$Object,
        [string]$Name,
        [object]$Value
    )
    if ($Object.PSObject.Properties.Name -contains $Name) {
        $Object.$Name = $Value
    }
    else {
        $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    }
}

function Ensure-Realm {
    <#
    Realm import only takes effect when the target realm does not already exist.
    If a local PostgreSQL volume already contains the realm, changing JSON and restarting
    Keycloak will not overwrite it. This script therefore patches the running realm through
    Admin API instead of requiring developers to delete local data.
    #>
    $realmRepresentation = Invoke-KeycloakAdminRoot -Method GET -Path "/realms/$Realm" -AllowNotFound
    if ($null -eq $realmRepresentation) {
        $body = @{
            realm = $Realm
            displayName = "DataSmart Govern Local Realm"
            enabled = $true
            sslRequired = "external"
            registrationAllowed = $false
            resetPasswordAllowed = $true
            editUsernameAllowed = $false
            bruteForceProtected = $true
        }
        Write-Change -Action "create realm '$Realm'" -Operation {
            Invoke-KeycloakAdminRoot -Method POST -Path "/realms" -Body $body | Out-Null
        }
        return
    }
    Set-Property -Object $realmRepresentation -Name "displayName" -Value "DataSmart Govern Local Realm"
    Set-Property -Object $realmRepresentation -Name "enabled" -Value $true
    Set-Property -Object $realmRepresentation -Name "bruteForceProtected" -Value $true
    Write-Change -Action "update realm '$Realm' base settings" -Operation {
        Invoke-KeycloakAdminRoot -Method PUT -Path "/realms/$Realm" -Body $realmRepresentation | Out-Null
    }
}

function New-RealmRoleProfile {
    param(
        [string]$Name,
        [string]$Description
    )
    return [pscustomobject]@{
        name = $Name
        description = $Description
    }
}

$RealmRoleProfiles = @(
    (New-RealmRoleProfile -Name "DATASMART_ORDINARY_USER" -Description "DataSmart ordinary user local sample role."),
    (New-RealmRoleProfile -Name "DATASMART_PROJECT_OWNER" -Description "DataSmart project owner local sample role."),
    (New-RealmRoleProfile -Name "DATASMART_OPERATOR" -Description "DataSmart operator local sample role."),
    (New-RealmRoleProfile -Name "DATASMART_AUDITOR" -Description "DataSmart auditor local sample role."),
    (New-RealmRoleProfile -Name "DATASMART_TENANT_ADMINISTRATOR" -Description "DataSmart tenant administrator role."),
    (New-RealmRoleProfile -Name "DATASMART_PLATFORM_ADMINISTRATOR" -Description "DataSmart platform administrator local sample role."),
    (New-RealmRoleProfile -Name "DATASMART_SERVICE_ACCOUNT" -Description "DataSmart service account local sample role.")
)

function Ensure-RealmRole {
    param([object]$RoleProfile)
    $roleName = [uri]::EscapeDataString($RoleProfile.name)
    $existingRole = Invoke-KeycloakAdmin -Method GET -Path "/roles/$roleName" -AllowNotFound
    if ($null -eq $existingRole) {
        Write-Change -Action "create realm role '$($RoleProfile.name)'" -Operation {
            Invoke-KeycloakAdmin -Method POST -Path "/roles" -Body $RoleProfile | Out-Null
        }
        return
    }
    Set-Property -Object $existingRole -Name "description" -Value $RoleProfile.description
    Write-Change -Action "update realm role '$($RoleProfile.name)' description" -Operation {
        Invoke-KeycloakAdmin -Method PUT -Path "/roles/$roleName" -Body $existingRole | Out-Null
    }
}

function Get-GatewayClient {
    $clientQuery = [uri]::EscapeDataString($GatewayClientId)
    $clients = @(Convert-KeycloakList -Response (Invoke-KeycloakAdmin -Method GET -Path "/clients?clientId=$clientQuery"))
    if ($clients.Count -eq 0) {
        return $null
    }
    return $clients[0]
}

function Ensure-GatewayClient {
    <#
    DataSmart gateway is the resource server and the public client used by local password-grant smoke tests.
    Production can replace it with a confidential enterprise IdP client or BFF login flow.
    The local closure path needs this client to provide aud=datasmart-gateway,
    DataSmart platform claims and the datasmart_project_ids candidate project claim.
    #>
    $client = Get-GatewayClient
    $clientBody = [ordered]@{
        clientId = $GatewayClientId
        name = "DataSmart Gateway"
        description = "Local DataSmart gateway OIDC client. Production should use a formally registered enterprise IdP client."
        enabled = $true
        protocol = "openid-connect"
        publicClient = $true
        bearerOnly = $false
        standardFlowEnabled = $true
        implicitFlowEnabled = $false
        directAccessGrantsEnabled = $true
        serviceAccountsEnabled = $false
        frontchannelLogout = $true
        fullScopeAllowed = $true
        redirectUris = @("http://localhost:3000/*", "http://localhost:5173/*", "http://localhost:8080/*")
        webOrigins = @("http://localhost:3000", "http://localhost:5173", "http://localhost:8080")
        attributes = @{
            "pkce.code.challenge.method" = "S256"
            "post.logout.redirect.uris" = "+"
        }
    }
    if ($null -eq $client) {
        Write-Change -Action "create OIDC client '$GatewayClientId'" -Operation {
            Invoke-KeycloakAdmin -Method POST -Path "/clients" -Body $clientBody | Out-Null
        }
    }
    else {
        $clientBody.id = $client.id
        Write-Change -Action "update OIDC client '$GatewayClientId' base settings" -Operation {
            Invoke-KeycloakAdmin -Method PUT -Path "/clients/$($client.id)" -Body $clientBody | Out-Null
        }
    }
    $refreshed = Get-GatewayClient
    if ($null -eq $refreshed) {
        throw "Unable to resolve gateway client '$GatewayClientId' after ensure operation."
    }
    return $refreshed.id
}

function New-AudienceMapper {
    return [pscustomobject]@{
        name = "datasmart-gateway-audience"
        protocol = "openid-connect"
        protocolMapper = "oidc-audience-mapper"
        consentRequired = $false
        config = @{
            "included.client.audience" = $GatewayClientId
            "id.token.claim" = "false"
            "access.token.claim" = "true"
            "introspection.token.claim" = "true"
        }
    }
}

function New-UserAttributeMapper {
    param(
        [string]$Name,
        [string]$UserAttribute,
        [string]$ClaimName,
        [switch]$Multivalued
    )
    $config = @{
        "user.attribute" = $UserAttribute
        "claim.name" = $ClaimName
        "jsonType.label" = "String"
        "id.token.claim" = "true"
        "access.token.claim" = "true"
        "userinfo.token.claim" = "true"
    }
    if ($Multivalued) {
        $config["multivalued"] = "true"
        $config["aggregate.attrs"] = "true"
    }
    return [pscustomobject]@{
        name = $Name
        protocol = "openid-connect"
        protocolMapper = "oidc-usermodel-attribute-mapper"
        consentRequired = $false
        config = $config
    }
}

function Ensure-ClientProtocolMapper {
    param(
        [string]$ClientUuid,
        [object]$Mapper
    )
    $mappers = @(Convert-KeycloakList -Response (Invoke-KeycloakAdmin -Method GET -Path "/clients/$ClientUuid/protocol-mappers/models"))
    $matches = @($mappers | Where-Object { $_.name -eq $Mapper.name })
    $existing = if ($matches.Count -gt 0) { $matches[0] } else { $null }
    if ($null -eq $existing) {
        Write-Change -Action "create protocol mapper '$($Mapper.name)'" -Operation {
            Invoke-KeycloakAdmin -Method POST -Path "/clients/$ClientUuid/protocol-mappers/models" -Body $Mapper | Out-Null
        }
        return
    }
    Set-Property -Object $Mapper -Name "id" -Value $existing.id
    Write-Change -Action "update protocol mapper '$($Mapper.name)'" -Operation {
        Invoke-KeycloakAdmin -Method PUT -Path "/clients/$ClientUuid/protocol-mappers/models/$($existing.id)" -Body $Mapper | Out-Null
    }
}

$ClientMappers = @(
    (New-AudienceMapper),
    (New-UserAttributeMapper -Name "datasmart-tenant-id" -UserAttribute "datasmart_tenant_id" -ClaimName "datasmart_tenant_id"),
    (New-UserAttributeMapper -Name "datasmart-actor-id" -UserAttribute "datasmart_actor_id" -ClaimName "datasmart_actor_id"),
    (New-UserAttributeMapper -Name "datasmart-actor-role" -UserAttribute "datasmart_actor_role" -ClaimName "datasmart_actor_role"),
    (New-UserAttributeMapper -Name "datasmart-actor-type" -UserAttribute "datasmart_actor_type" -ClaimName "datasmart_actor_type"),
    (New-UserAttributeMapper -Name "datasmart-workspace-id" -UserAttribute "datasmart_workspace_id" -ClaimName "datasmart_workspace_id"),
    (New-UserAttributeMapper -Name "datasmart-application-id" -UserAttribute "datasmart_application_id" -ClaimName "datasmart_application_id"),
    (New-UserAttributeMapper -Name "datasmart-application-code" -UserAttribute "datasmart_application_code" -ClaimName "datasmart_application_code"),
    (New-UserAttributeMapper -Name "datasmart-project-ids" -UserAttribute "datasmart_project_ids" -ClaimName "datasmart_project_ids" -Multivalued)
)

function New-ManagedUserProfileAttribute {
    param(
        [string]$Name,
        [string]$DisplayName,
        [bool]$Multivalued
    )
    return [pscustomobject]@{
        name = $Name
        displayName = $DisplayName
        permissions = @{
            view = @("admin")
            edit = @("admin")
        }
        validations = @{
            length = @{
                max = 255
            }
        }
        multivalued = $Multivalued
        group = "datasmart-context"
    }
}

$ManagedUserProfileAttributes = @(
    (New-ManagedUserProfileAttribute -Name "datasmart_tenant_id" -DisplayName "DataSmart tenant id" -Multivalued $false),
    (New-ManagedUserProfileAttribute -Name "datasmart_actor_id" -DisplayName "DataSmart actor id" -Multivalued $false),
    (New-ManagedUserProfileAttribute -Name "datasmart_actor_role" -DisplayName "DataSmart actor role" -Multivalued $false),
    (New-ManagedUserProfileAttribute -Name "datasmart_actor_type" -DisplayName "DataSmart actor type" -Multivalued $false),
    (New-ManagedUserProfileAttribute -Name "datasmart_workspace_id" -DisplayName "DataSmart runtime workspace id" -Multivalued $false),
    (New-ManagedUserProfileAttribute -Name "datasmart_application_id" -DisplayName "DataSmart application id" -Multivalued $false),
    (New-ManagedUserProfileAttribute -Name "datasmart_application_code" -DisplayName "DataSmart application code" -Multivalued $false),
    (New-ManagedUserProfileAttribute -Name "datasmart_project_ids" -DisplayName "DataSmart candidate project ids" -Multivalued $true)
)

function Ensure-UserProfileManagedAttributes {
    <#
    Keycloak 26 enables the declarative user profile model by default. If a custom
    attribute is not declared as a managed user-profile attribute, Admin REST updates
    can be filtered out by the user-profile policy. DataSmart claims are control-plane
    identity facts, so they are declared as admin-only managed attributes instead of
    letting end users create or edit arbitrary unmanaged attributes.
    #>
    $profile = Invoke-KeycloakAdmin -Method GET -Path "/users/profile"
    if (-not ($profile.PSObject.Properties.Name -contains "attributes") -or $null -eq $profile.attributes) {
        Set-Property -Object $profile -Name "attributes" -Value @()
    }
    if (-not ($profile.PSObject.Properties.Name -contains "groups") -or $null -eq $profile.groups) {
        Set-Property -Object $profile -Name "groups" -Value @()
    }
    if (-not ($profile.PSObject.Properties.Name -contains "unmanagedAttributePolicy")) {
        Set-Property -Object $profile -Name "unmanagedAttributePolicy" -Value "ADMIN_EDIT"
    }
    else {
        $profile.unmanagedAttributePolicy = "ADMIN_EDIT"
    }

    $groups = @(Convert-KeycloakList -Response $profile.groups)
    $hasDataSmartGroup = @($groups | Where-Object { $_.name -eq "datasmart-context" }).Count -gt 0
    if (-not $hasDataSmartGroup) {
        $groups += [pscustomobject]@{
            name = "datasmart-context"
            displayHeader = "DataSmart context"
            displayDescription = "Low-sensitive DataSmart tenant, actor, application and project claims."
        }
    }

    $existingAttributes = @(Convert-KeycloakList -Response $profile.attributes)
    $mergedAttributes = @()
    foreach ($attribute in $existingAttributes) {
        if (@($ManagedUserProfileAttributes | Where-Object { $_.name -eq $attribute.name }).Count -eq 0) {
            $mergedAttributes += $attribute
        }
    }
    foreach ($attribute in $ManagedUserProfileAttributes) {
        $mergedAttributes += $attribute
    }
    Set-Property -Object $profile -Name "groups" -Value $groups
    Set-Property -Object $profile -Name "attributes" -Value $mergedAttributes

    Write-Change -Action "align Keycloak user-profile managed DataSmart attributes" -Operation {
        Invoke-KeycloakAdmin -Method PUT -Path "/users/profile" -Body $profile | Out-Null
    }
}

function New-UserProfile {
    param(
        [string]$Username,
        [string]$FirstName,
        [string]$LastName,
        [string]$Email,
        [string]$TenantId,
        [string]$ActorId,
        [string]$ActorRole,
        [string]$ActorType,
        [string]$WorkspaceId,
        [string]$ApplicationId,
        [string]$ApplicationCode,
        [string[]]$ProjectIds,
        [string[]]$RealmRoles
    )
    return [pscustomobject]@{
        username = $Username
        firstName = $FirstName
        lastName = $LastName
        email = $Email
        tenantId = $TenantId
        actorId = $ActorId
        actorRole = $ActorRole
        actorType = $ActorType
        workspaceId = $WorkspaceId
        applicationId = $ApplicationId
        applicationCode = $ApplicationCode
        projectIds = $ProjectIds
        realmRoles = $RealmRoles
    }
}

$UserProfiles = @(
    (New-UserProfile -Username "ordinary-user" -FirstName "Ordinary" -LastName "User" -Email "ordinary-user@example.local" -TenantId "10" -ActorId "1004" -ActorRole "ORDINARY_USER" -ActorType "USER" -WorkspaceId "workspace-a" -ApplicationId "10010" -ApplicationCode "FLASHSYNC" -ProjectIds @("101") -RealmRoles @("DATASMART_ORDINARY_USER")),
    (New-UserProfile -Username "project-owner" -FirstName "Project" -LastName "Owner" -Email "project-owner@example.local" -TenantId "10" -ActorId "1001" -ActorRole "PROJECT_OWNER" -ActorType "USER" -WorkspaceId "workspace-a" -ApplicationId "10010" -ApplicationCode "FLASHSYNC" -ProjectIds @("101") -RealmRoles @("DATASMART_PROJECT_OWNER")),
    (New-UserProfile -Username "operator" -FirstName "DataSmart" -LastName "Operator" -Email "operator@example.local" -TenantId "10" -ActorId "1002" -ActorRole "OPERATOR" -ActorType "USER" -WorkspaceId "workspace-a" -ApplicationId "10010" -ApplicationCode "FLASHSYNC" -ProjectIds @("101") -RealmRoles @("DATASMART_OPERATOR")),
    (New-UserProfile -Username "auditor" -FirstName "DataSmart" -LastName "Auditor" -Email "auditor@example.local" -TenantId "10" -ActorId "1003" -ActorRole "AUDITOR" -ActorType "USER" -WorkspaceId "workspace-a" -ApplicationId "10010" -ApplicationCode "FLASHSYNC" -ProjectIds @("101") -RealmRoles @("DATASMART_AUDITOR")),
    (New-UserProfile -Username "platform-admin" -FirstName "Platform" -LastName "Admin" -Email "platform-admin@example.local" -TenantId "1" -ActorId "9001" -ActorRole "PLATFORM_ADMINISTRATOR" -ActorType "USER" -WorkspaceId "platform" -ApplicationId "9000" -ApplicationCode "DATASMART_PLATFORM" -ProjectIds @("900") -RealmRoles @("DATASMART_PLATFORM_ADMINISTRATOR")),
    (New-UserProfile -Username "sync-service" -FirstName "Sync" -LastName "Service" -Email "sync-service@example.local" -TenantId "10" -ActorId "9101" -ActorRole "SERVICE_ACCOUNT" -ActorType "SERVICE_ACCOUNT" -WorkspaceId "system-sync" -ApplicationId "10010" -ApplicationCode "FLASHSYNC" -ProjectIds @("101") -RealmRoles @("DATASMART_SERVICE_ACCOUNT"))
)

function Get-UserByUsername {
    param([string]$Username)
    $encodedUsername = [uri]::EscapeDataString($Username)
    $users = @(Convert-KeycloakList -Response (Invoke-KeycloakAdmin -Method GET -Path "/users?username=$encodedUsername&exact=true"))
    if ($users.Count -eq 0) {
        return $null
    }
    return $users[0]
}

function Build-UserRepresentation {
    param([object]$UserProfile)
    return [pscustomobject]@{
        username = $UserProfile.username
        enabled = $true
        emailVerified = $true
        firstName = $UserProfile.firstName
        lastName = $UserProfile.lastName
        email = $UserProfile.email
        attributes = @{
            "datasmart_tenant_id" = @($UserProfile.tenantId)
            "datasmart_actor_id" = @($UserProfile.actorId)
            "datasmart_actor_role" = @($UserProfile.actorRole)
            "datasmart_actor_type" = @($UserProfile.actorType)
            "datasmart_workspace_id" = @($UserProfile.workspaceId)
            "datasmart_application_id" = @($UserProfile.applicationId)
            "datasmart_application_code" = @($UserProfile.applicationCode)
            "datasmart_project_ids" = @($UserProfile.projectIds)
        }
    }
}

function Ensure-UserRoleMappings {
    param(
        [string]$UserId,
        [string[]]$RoleNames
    )
    if ($RoleNames.Count -eq 0) {
        return
    }
    $currentMappings = @(Convert-KeycloakList -Response (Invoke-KeycloakAdmin -Method GET -Path "/users/$UserId/role-mappings/realm"))
    $currentNames = @($currentMappings | ForEach-Object { $_.name })
    $missingRoles = @()
    foreach ($roleName in $RoleNames) {
        if ($currentNames -notcontains $roleName) {
            $roleRepresentation = Invoke-KeycloakAdmin -Method GET -Path "/roles/$([uri]::EscapeDataString($roleName))"
            $missingRoles += $roleRepresentation
        }
    }
    if ($missingRoles.Count -eq 0) {
        Write-Host "[OK] userId=$UserId realm roles already aligned"
        return
    }
    Write-Change -Action "add missing realm roles '$($RoleNames -join ",")' to userId=$UserId" -Operation {
        Invoke-KeycloakAdmin -Method POST -Path "/users/$UserId/role-mappings/realm" -Body $missingRoles | Out-Null
    }
}

function Reset-LocalUserPassword {
    param(
        [string]$UserId,
        [string]$Username
    )
    if ($SkipPasswordReset) {
        Write-Host "[SKIP] password reset for '$Username'"
        return
    }
    $credential = @{
        type = "password"
        value = $LocalUserPassword
        temporary = $false
    }
    Write-Change -Action "reset local password for '$Username' without printing secret" -Operation {
        Invoke-KeycloakAdmin -Method PUT -Path "/users/$UserId/reset-password" -Body $credential | Out-Null
    }
}

function Clear-UserBruteForceState {
    param(
        [string]$UserId,
        [string]$Username
    )
    if ($SkipBruteForceClear) {
        return
    }
    Write-Change -Action "clear local brute-force lock state for '$Username'" -Operation {
        Invoke-KeycloakAdmin -Method DELETE -Path "/attack-detection/brute-force/users/$UserId" -AllowNotFound | Out-Null
    }
}

function Ensure-User {
    param([object]$UserProfile)
    $user = Get-UserByUsername -Username $UserProfile.username
    $desired = Build-UserRepresentation -UserProfile $UserProfile
    if ($null -eq $user) {
        Write-Change -Action "create user '$($UserProfile.username)' with DataSmart attributes" -Operation {
            Invoke-KeycloakAdmin -Method POST -Path "/users" -Body $desired | Out-Null
        }
        $user = Get-UserByUsername -Username $UserProfile.username
        if ($null -eq $user) {
            throw "Unable to resolve user '$($UserProfile.username)' after create operation."
        }
    }
    else {
        Set-Property -Object $desired -Name "id" -Value $user.id
        Write-Change -Action "update user '$($UserProfile.username)' DataSmart attributes" -Operation {
            Invoke-KeycloakAdmin -Method PUT -Path "/users/$($user.id)" -Body $desired | Out-Null
        }
    }
    Ensure-UserRoleMappings -UserId $user.id -RoleNames $UserProfile.realmRoles
    Reset-LocalUserPassword -UserId $user.id -Username $UserProfile.username
    Clear-UserBruteForceState -UserId $user.id -Username $UserProfile.username
}

Write-Step "sync Keycloak realm baseline: realm=$Realm baseUrl=$KeycloakBaseUrl apply=$Apply"
Ensure-Realm

Write-Step "sync DataSmart realm roles"
foreach ($roleProfile in $RealmRoleProfiles) {
    Ensure-RealmRole -RoleProfile $roleProfile
}

Write-Step "sync datasmart-gateway client and protocol mappers"
$clientUuid = Ensure-GatewayClient
foreach ($mapper in $ClientMappers) {
    Ensure-ClientProtocolMapper -ClientUuid $clientUuid -Mapper $mapper
}

Write-Step "sync Keycloak user-profile managed DataSmart attributes"
Ensure-UserProfileManagedAttributes

Write-Step "sync local sample users, DataSmart low-sensitive claims and realm roles"
foreach ($userProfile in $UserProfiles) {
    Ensure-User -UserProfile $userProfile
}

if ($Apply) {
    Write-Host "[DONE] Keycloak DataSmart claims synced. Please fetch a new access token; existing tokens will not gain new claims."
}
else {
    Write-Host "[DONE] Dry-run finished. Re-run with -Apply to write Keycloak."
}
