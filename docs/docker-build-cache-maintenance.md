# Docker Build Cache Maintenance

DataSmart Govern builds several Java and frontend images. Repeated BuildKit
builds retain Maven dependencies, package-manager layers, and intermediate
image layers, so a development workstation can accumulate tens of gigabytes
of build cache over time.

## Safe inspection

Run the repository helper without arguments to report Docker disk usage:

```powershell
.\scripts\docker-build-cache-maintenance.ps1
```

Report mode does not change Docker state.

## Enforce a cache limit

Use the default 10 GB limit:

```powershell
.\scripts\docker-build-cache-maintenance.ps1 -Prune
```

Choose a different limit when a workstation has more available disk space:

```powershell
.\scripts\docker-build-cache-maintenance.ps1 -Prune -MaxUsedSpace 20GB
```

The script runs `docker buildx prune --all --max-used-space <limit>`. It only
removes old BuildKit cache records. It does not remove images, running or
stopped containers, networks, or volumes. The next image build may download
some dependencies again after old cache records are reclaimed.

Run the command after large rebuild bursts or as regular workstation
maintenance. Use `-WhatIf` with `-Prune` to inspect the intended operation.

## Emergency full reset

If a corrupted or unexpectedly large cache must be removed completely, use:

```powershell
docker buildx prune --all --force
```

Do not use `docker system prune --volumes` for this problem. That broader
command can remove unrelated images and persistent business data volumes.

After a full reset, `docker buildx du` can still show a few active records with
size `0B`. These are active builder references, not retained disk usage.
