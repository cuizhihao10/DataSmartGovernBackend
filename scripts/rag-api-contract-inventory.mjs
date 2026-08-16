/**
 * 从 DataSmart Govern 实际源码构建 RAG 接口合同目录。
 *
 * 这份目录只读取仓库中的 Java Controller、FastAPI 路由和 DTO 源码，不启动应用、不连接网络，
 * 也不会读取环境变量或 Secret。接口文档因此可以持续回答“系统当前真实提供了什么接口”，而不是
 * 依赖人工编造一批看似丰富、实际不存在的 URL。
 */

import fs from "node:fs/promises";
import path from "node:path";

const JAVA_MODULES = [
  "gateway",
  "permission-admin",
  "task-management",
  "datasource-management",
  "data-quality",
  "observability",
  "data-sync",
  "agent-runtime",
];

const JAVA_MAPPING_NAMES = new Set([
  "GetMapping",
  "PostMapping",
  "PutMapping",
  "PatchMapping",
  "DeleteMapping",
  "RequestMapping",
]);

const HTTP_METHOD_BY_ANNOTATION = {
  GetMapping: "GET",
  PostMapping: "POST",
  PutMapping: "PUT",
  PatchMapping: "PATCH",
  DeleteMapping: "DELETE",
};

/**
 * 扫描整个仓库并返回稳定排序的真实接口合同。
 *
 * 返回结果刻意包含来源文件和行号，文档维护者可以从每一个接口条目回到 Controller；请求参数与
 * DTO 字段只用于生成合成示例，不会执行 Controller，也不会把源码中的常量值当成凭据。
 */
export async function collectActualApiContracts(repositoryRoot) {
  const resolvedRoot = path.resolve(repositoryRoot);
  const javaFiles = [];
  for (const moduleName of JAVA_MODULES) {
    const sourceRoot = path.join(resolvedRoot, moduleName, "src", "main", "java");
    for (const sourceFile of await listFiles(sourceRoot, (file) => file.endsWith(".java"))) {
      javaFiles.push({ moduleName, sourceFile });
    }
  }
  const javaTypeIndex = buildTypeFileIndex(javaFiles.map((item) => item.sourceFile));
  const platformHeaderConstants = await loadStringConstants(
    path.join(
      resolvedRoot,
      "platform-common",
      "src",
      "main",
      "java",
      "com",
      "czh",
      "datasmart",
      "govern",
      "common",
      "context",
      "PlatformContextHeaders.java",
    ),
  );

  const contracts = [];
  for (const { moduleName, sourceFile } of javaFiles) {
    if (!sourceFile.endsWith("Controller.java")) continue;
    const sourceText = await fs.readFile(sourceFile, "utf8");
    contracts.push(...await parseJavaController({
      repositoryRoot: resolvedRoot,
      moduleName,
      sourceFile,
      sourceText,
      javaTypeIndex,
      platformHeaderConstants,
    }));
  }
  contracts.push(...await parsePythonApiRoutes(resolvedRoot));

  const deduplicated = new Map();
  for (const contract of contracts) {
    const identity = [
      contract.transport,
      contract.httpMethod,
      contract.module,
      contract.controller,
      contract.methodName,
      contract.declaredPaths.join("|"),
    ].join(":");
    if (!deduplicated.has(identity)) deduplicated.set(identity, contract);
  }
  return [...deduplicated.values()]
    .sort((left, right) => (
      left.module.localeCompare(right.module)
      || left.controller.localeCompare(right.controller)
      || left.sourceLine - right.sourceLine
      || left.httpMethod.localeCompare(right.httpMethod)
    ))
    .map((contract, index) => ({
      ...contract,
      contractId: `API-${String(index + 1).padStart(4, "0")}`,
    }));
}

/** 根据接口文档主题筛选对应的真实合同，综合接口文档返回完整目录。 */
export function apiContractsForTopic(contracts, topicSlug) {
  if (topicSlug === "reference-api-websocket") return contracts;
  if (topicSlug === "reference-authentication-api") {
    return contracts.filter((item) => (
      item.module === "permission-admin"
      || item.module === "gateway"
      || /identity|permission|approval|tenant|project|auth/i.test(item.searchText)
    ));
  }
  if (topicSlug === "reference-agent-api") {
    return contracts.filter((item) => (
      item.module === "agent-runtime"
      || item.module === "python-ai-runtime"
      || /agent|specialist|model|rag/i.test(item.searchText)
    ));
  }
  if (topicSlug === "reference-task-api") {
    return contracts.filter((item) => (
      item.module === "task-management"
      || /task-draft|\/tasks(?:\/|$)/i.test(item.searchText)
    ));
  }
  if (topicSlug === "reference-data-sync-api") {
    return contracts.filter((item) => (
      item.module === "data-sync"
      || item.module === "datasource-management"
      || /data-sync|datasource|sync-task|connector/i.test(item.searchText)
    ));
  }
  if (topicSlug === "reference-recovery-api") {
    return contracts.filter((item) => (
      /recovery|recover|retry|replay|checkpoint|incident|attention|repair|remediation/i
        .test(item.searchText)
    ));
  }
  if (topicSlug === "reference-websocket-events") {
    return contracts.filter((item) => (
      item.transport !== "REST"
      || /events\/ws|plans\/stream|replay|runtime-event/i.test(item.searchText)
    ));
  }
  return [];
}

/** 递归列出满足条件的文件；目录不存在时返回空集合，方便精简模块仍可生成文档。 */
async function listFiles(root, predicate) {
  let entries;
  try {
    entries = await fs.readdir(root, { withFileTypes: true });
  } catch (error) {
    if (error && error.code === "ENOENT") return [];
    throw error;
  }
  const files = [];
  for (const entry of entries) {
    const target = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...await listFiles(target, predicate));
    else if (entry.isFile() && predicate(target)) files.push(target);
  }
  return files.sort();
}

/** 为 DTO、Entity 和 record 建立简单类名到源码路径的索引。 */
function buildTypeFileIndex(files) {
  const index = new Map();
  for (const file of files) {
    const simpleName = path.basename(file, ".java");
    if (!index.has(simpleName)) index.set(simpleName, file);
  }
  return index;
}

/** 读取 Java 字符串常量，用于把 PlatformContextHeaders.X 还原成人类可读 Header 名。 */
async function loadStringConstants(sourceFile) {
  let text = "";
  try {
    text = await fs.readFile(sourceFile, "utf8");
  } catch (error) {
    if (error && error.code === "ENOENT") return new Map();
    throw error;
  }
  const constants = new Map();
  for (const match of text.matchAll(/public\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"\s*;/g)) {
    constants.set(match[1], match[2]);
  }
  return constants;
}

/** 解析一个 Spring Controller 的类级路径、方法级映射、参数、返回类型和中文 Javadoc。 */
async function parseJavaController(context) {
  const {
    repositoryRoot,
    moduleName,
    sourceFile,
    sourceText,
    javaTypeIndex,
    platformHeaderConstants,
  } = context;
  const classMatch = /\bpublic\s+(?:final\s+)?class\s+(\w+)/.exec(sourceText);
  if (!classMatch) return [];
  const className = classMatch[1];
  const classIndex = classMatch.index;
  const annotations = readJavaAnnotations(sourceText);
  const classMapping = [...annotations]
    .filter((item) => item.name === "RequestMapping" && item.start < classIndex)
    .at(-1);
  const classPaths = classMapping ? annotationPaths(classMapping.raw) : [""];
  const contracts = [];

  for (const annotation of annotations) {
    if (!JAVA_MAPPING_NAMES.has(annotation.name) || annotation.start < classIndex) continue;
    const method = readJavaMethodAfter(sourceText, annotation.end);
    if (!method) continue;
    const methodPaths = annotationPaths(annotation.raw);
    const declaredPaths = combinePaths(classPaths, methodPaths);
    const httpMethods = annotationHttpMethods(annotation.name, annotation.raw);
    const javadoc = nearestJavadoc(sourceText, annotation.start, method.previousBoundary);
    const paramDescriptions = javadocParamDescriptions(javadoc);
    const parameters = parseJavaParameters(
      method.parametersText,
      platformHeaderConstants,
      paramDescriptions,
    );
    const requestBodyParameter = parameters.find((item) => item.location === "BODY");
    const requestSchema = requestBodyParameter
      ? await resolveJavaSchema(requestBodyParameter.javaType, javaTypeIndex)
      : null;
    const responseSchema = await resolveJavaSchema(method.returnType, javaTypeIndex);
    const externalPaths = declaredPaths
      .map((declaredPath) => externalPathFor(moduleName, declaredPath))
      .filter(Boolean);
    const visibility = declaredPaths.some((item) => /(^|\/)internal(\/|$)/.test(item))
      || /Internal|Callback|WorkerLease|Outbox/.test(className)
      ? "INTERNAL"
      : "PUBLIC";

    for (const httpMethod of httpMethods) {
      const transport = annotationProducesStream(annotation.raw) ? "SSE" : "REST";
      const purpose = javadocSummary(javadoc) || `${className}.${method.methodName} 接口`;
      const relativeSource = path.relative(repositoryRoot, sourceFile).replaceAll("\\", "/");
      contracts.push(finalizeContract({
        module: moduleName,
        controller: className,
        methodName: method.methodName,
        sourceFile: relativeSource,
        sourceLine: lineNumberAt(sourceText, annotation.start),
        transport,
        httpMethod,
        visibility,
        declaredPaths,
        externalPaths: uniqueStrings(externalPaths),
        purpose,
        parameters,
        requestType: requestBodyParameter?.javaType ?? null,
        requestSchema,
        responseType: method.returnType,
        responseSchema,
        consumes: annotationMediaTypes(annotation.raw, "consumes"),
        produces: annotationMediaTypes(annotation.raw, "produces"),
      }));
    }
  }
  return contracts;
}

/** 读取源码中的所有 Spring Mapping 注解，并保留原始字符位置。 */
function readJavaAnnotations(text) {
  const annotations = [];
  const pattern = /@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)\b/g;
  for (const match of text.matchAll(pattern)) {
    let end = match.index + match[0].length;
    while (/\s/.test(text[end] ?? "")) end += 1;
    if (text[end] === "(") end = scanBalanced(text, end, "(", ")") + 1;
    annotations.push({ name: match[1], start: match.index, end, raw: text.slice(match.index, end) });
  }
  return annotations;
}

/** 从 Mapping 注解后读取 public 方法签名；找不到明确方法时忽略该注解。 */
function readJavaMethodAfter(text, annotationEnd) {
  const searchEnd = Math.min(text.length, annotationEnd + 6000);
  const segment = text.slice(annotationEnd, searchEnd);
  const publicMatch = /\bpublic\s+/.exec(segment);
  if (!publicMatch) return null;
  const methodStart = annotationEnd + publicMatch.index;
  const openParen = text.indexOf("(", methodStart);
  if (openParen < 0 || openParen >= searchEnd) return null;
  const prefix = text.slice(methodStart, openParen).replace(/\s+/g, " ").trim();
  if (/\bclass\b|\binterface\b/.test(prefix)) return null;
  const nameMatch = /(\w+)\s*$/.exec(prefix);
  if (!nameMatch) return null;
  const methodName = nameMatch[1];
  const returnType = prefix
    .slice("public".length, nameMatch.index)
    .replace(/\b(?:static|final|synchronized|default)\b/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (!returnType) return null;
  const closeParen = scanBalanced(text, openParen, "(", ")");
  if (closeParen < openParen) return null;
  return {
    methodName,
    returnType,
    parametersText: text.slice(openParen + 1, closeParen),
    previousBoundary: Math.max(0, text.lastIndexOf("}", annotationEnd - 1)),
  };
}

/** 把 Java 方法参数分成 Path、Query、Header、Body、Part 或隐式模型参数。 */
function parseJavaParameters(parametersText, headerConstants, descriptions) {
  return splitTopLevel(parametersText, ",")
    .map((rawParameter) => rawParameter.trim())
    .filter(Boolean)
    .map((rawParameter) => {
      const location = rawParameter.includes("@PathVariable") ? "PATH"
        : rawParameter.includes("@RequestParam") ? "QUERY"
          : rawParameter.includes("@RequestHeader") ? "HEADER"
            : rawParameter.includes("@RequestBody") ? "BODY"
              : rawParameter.includes("@RequestPart") ? "PART"
                : "MODEL";
      const withoutAnnotations = stripParameterAnnotations(rawParameter);
      const nameMatch = /(\w+)\s*$/.exec(withoutAnnotations);
      const variableName = nameMatch?.[1] ?? "value";
      const javaType = nameMatch
        ? withoutAnnotations.slice(0, nameMatch.index).replace(/\bfinal\b/g, "").trim()
        : "Object";
      const annotationName = annotationArgumentName(rawParameter, location);
      let name = annotationName || variableName;
      if (location === "HEADER") {
        const constantMatch = /PlatformContextHeaders\.(\w+)/.exec(rawParameter);
        if (constantMatch && headerConstants.has(constantMatch[1])) {
          name = headerConstants.get(constantMatch[1]);
        }
      }
      const required = !/required\s*=\s*false/.test(rawParameter)
        && !/Optional\s*</.test(javaType)
        && !/defaultValue\s*=/.test(rawParameter);
      const defaultValueMatch = /defaultValue\s*=\s*"([^"]*)"/.exec(rawParameter);
      return {
        name,
        variableName,
        location,
        javaType,
        required,
        defaultValue: defaultValueMatch?.[1] ?? null,
        description: descriptions.get(variableName) || parameterDefaultDescription(name, location),
      };
    });
}

/** 去除参数上的注解及其括号，让剩余文本稳定为“类型 变量名”。 */
function stripParameterAnnotations(raw) {
  let result = raw;
  while (true) {
    const match = /@\w+(?:\.\w+)*\s*/.exec(result);
    if (!match) break;
    const at = match.index;
    let end = at + match[0].length;
    if (result[end] === "(") end = scanBalanced(result, end, "(", ")") + 1;
    result = `${result.slice(0, at)} ${result.slice(end)}`;
  }
  return result.replace(/\s+/g, " ").trim();
}

/** 从参数注解的字符串参数中读取显式参数名。 */
function annotationArgumentName(rawParameter, location) {
  const annotation = {
    PATH: "PathVariable",
    QUERY: "RequestParam",
    HEADER: "RequestHeader",
    BODY: "RequestBody",
    PART: "RequestPart",
  }[location];
  if (!annotation) return null;
  const start = rawParameter.indexOf(`@${annotation}`);
  if (start < 0) return null;
  const open = rawParameter.indexOf("(", start);
  if (open < 0) return null;
  const close = scanBalanced(rawParameter, open, "(", ")");
  const body = rawParameter.slice(open + 1, close);
  const named = /(?:value|name)\s*=\s*"([^"]+)"/.exec(body);
  const positional = /^\s*"([^"]+)"/.exec(body);
  return named?.[1] ?? positional?.[1] ?? null;
}

/** 解析 Java record、枚举或 Lombok DTO 的第一层字段，用于接口文档 Schema 和示例。 */
async function resolveJavaSchema(javaType, typeIndex, visited = new Set()) {
  const normalizedType = normalizeJavaType(javaType);
  const candidateNames = typeCandidates(normalizedType);
  const simpleName = candidateNames.find((name) => typeIndex.has(name));
  if (!simpleName || visited.has(simpleName)) {
    return { type: normalizedType, fields: [], enumValues: [] };
  }
  visited.add(simpleName);
  const sourceFile = typeIndex.get(simpleName);
  const text = await fs.readFile(sourceFile, "utf8");
  const enumMatch = new RegExp(`\\benum\\s+${escapeRegex(simpleName)}\\s*\\{([\\s\\S]*?)\\n\\s*[;} ]`).exec(text);
  if (enumMatch) {
    const enumValues = enumMatch[1]
      .split(/[,;\n]/)
      .map((value) => value.trim())
      .filter((value) => /^[A-Z][A-Z0-9_]*$/.test(value))
      .slice(0, 30);
    return { type: simpleName, fields: [], enumValues };
  }

  const fields = [];
  const recordStart = text.search(new RegExp(`\\brecord\\s+${escapeRegex(simpleName)}\\s*\\(`));
  if (recordStart >= 0) {
    const open = text.indexOf("(", recordStart);
    const close = scanBalanced(text, open, "(", ")");
    for (const component of splitTopLevel(text.slice(open + 1, close), ",")) {
      const clean = stripParameterAnnotations(component).replace(/\s+/g, " ").trim();
      const match = /(.+?)\s+(\w+)$/.exec(clean);
      if (match) fields.push(schemaField(match[2], match[1], true));
    }
  } else {
    for (const match of text.matchAll(/(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?([\w.<>, ?\[\]]+)\s+(\w+)\s*(?:=[^;]*)?;/g)) {
      if (match[2] === "serialVersionUID") continue;
      fields.push(schemaField(match[2], match[1], !isNullableField(text, match.index)));
      if (fields.length >= 80) break;
    }
  }
  return {
    type: simpleName,
    sourceFile: sourceFile.replaceAll("\\", "/"),
    fields,
    enumValues: [],
  };
}

/** 为 Schema 字段生成稳定、低敏且可读的合成示例。 */
function schemaField(name, javaType, required) {
  return {
    name,
    javaType: normalizeJavaType(javaType),
    required,
    example: sampleValue(name, javaType),
  };
}

/** 根据字段名和 Java 类型生成不含真实凭据、个人数据或连接串的示例值。 */
function sampleValue(name, javaType) {
  const lower = name.toLowerCase();
  const type = String(javaType || "").toLowerCase();
  if (/password|secret|token|credential|accesskey|apikey/.test(lower)) return "<由 Secret 注入，不在请求示例展示>";
  if (type.includes("boolean") || /^is[A-Z]/.test(name)) return true;
  if (/list|set|collection|\[\]/.test(type)) return [];
  if (/map|object/.test(type)) return {};
  if (/long|integer|int|short|bigdecimal|double|float/.test(type)) {
    if (lower.includes("tenant")) return 10;
    if (lower.includes("project")) return 101;
    if (lower.includes("application")) return 1001;
    if (lower.includes("task")) return 120;
    if (lower.includes("execution")) return 2919;
    if (lower.includes("current") || lower === "page") return 1;
    if (lower.includes("size") || lower.includes("limit")) return 20;
    return 1;
  }
  if (/instant|localdatetime|offsetdatetime|date/.test(type) || /(?:at|time|date)$/.test(lower)) {
    return "2026-08-16T10:00:00+08:00";
  }
  if (lower.includes("trace")) return "trace-doc-example-001";
  if (lower.includes("requestid")) return "request-doc-example-001";
  if (lower.includes("session")) return "session-doc-example-001";
  if (lower.includes("runid")) return "run-doc-example-001";
  if (lower.includes("plan")) return "plan-doc-example-001";
  if (lower.includes("role")) return "PROJECT_OWNER";
  if (lower.includes("state") || lower.includes("status")) return "ENABLED";
  if (lower.includes("name")) return "合成示例名称";
  if (lower.includes("description") || lower.includes("reason") || lower.includes("remark")) return "合成示例说明";
  if (lower.includes("url") || lower.includes("uri") || lower.includes("endpoint")) return "https://service.example.invalid/api";
  return "示例值";
}

/** 把解析结果补齐为文档可直接渲染的请求、响应、错误和权限说明。 */
function finalizeContract(contract) {
  const primaryPath = contract.externalPaths[0] || contract.declaredPaths[0] || "/";
  const requestExample = buildRequestExample(contract, primaryPath);
  const responseExample = buildResponseExample(contract);
  const errorResponses = buildErrorResponses(contract);
  const searchText = [
    contract.module,
    contract.controller,
    contract.methodName,
    contract.purpose,
    ...contract.declaredPaths,
    ...contract.externalPaths,
  ].join(" ");
  return {
    ...contract,
    requestExample,
    responseExample,
    errorResponses,
    permission: contract.visibility === "INTERNAL"
      ? "仅服务身份调用；同时校验来源服务、签名或内部令牌，并保留审计。"
      : "调用方先通过 Gateway 认证与资源/动作授权，业务服务再执行租户、项目和对象范围复核。",
    idempotency: ["POST", "PUT", "PATCH", "DELETE"].includes(contract.httpMethod)
      ? "写操作按接口实现携带幂等键、版本或业务唯一键；同键异请求必须拒绝。"
      : "只读请求不得产生业务状态变更；分页、过滤和范围参数必须稳定。",
    searchText,
  };
}

/** 构造可复制阅读的 HTTP 请求示例，所有身份值均为占位符或合成值。 */
function buildRequestExample(contract, primaryPath) {
  let requestPath = primaryPath;
  for (const parameter of contract.parameters.filter((item) => item.location === "PATH")) {
    requestPath = requestPath.replace(`{${parameter.name}}`, encodeURIComponent(String(sampleValue(parameter.name, parameter.javaType))));
  }
  const query = contract.parameters
    .filter((item) => item.location === "QUERY")
    .slice(0, 12)
    .map((item) => `${encodeURIComponent(item.name)}=${encodeURIComponent(String(item.defaultValue ?? sampleValue(item.name, item.javaType)))}`)
    .join("&");
  if (query) requestPath += `${requestPath.includes("?") ? "&" : "?"}${query}`;
  const lines = [`${contract.httpMethod} ${requestPath} HTTP/1.1`];
  if (contract.visibility === "PUBLIC") lines.push("Authorization: Bearer <access-token>");
  else lines.push("X-Source-Service: <受信服务身份>");
  lines.push("X-Trace-Id: trace-doc-example-001");
  const body = contract.requestSchema?.fields?.length
    ? Object.fromEntries(contract.requestSchema.fields.slice(0, 30).map((field) => [field.name, field.example]))
    : null;
  if (body) {
    lines.push("Content-Type: application/json", "", JSON.stringify(body, null, 2));
  }
  return lines.join("\n");
}

/** 根据返回类型生成平台统一响应或二进制下载说明。 */
function buildResponseExample(contract) {
  if (/ResponseEntity\s*<\s*byte\[\]/.test(contract.responseType)) {
    return "HTTP/1.1 200 OK\nContent-Type: application/octet-stream\nContent-Disposition: attachment; filename=<export-file>\n\n<binary body>";
  }
  const data = contract.responseSchema?.fields?.length
    ? Object.fromEntries(contract.responseSchema.fields.slice(0, 30).map((field) => [field.name, field.example]))
    : { responseType: contract.responseType || "void" };
  return JSON.stringify({
    success: true,
    code: "SUCCESS",
    message: "操作成功",
    data,
    traceId: "trace-doc-example-001",
  }, null, 2);
}

/** 为接口文档生成通用且语义正确的错误响应，不伪造事故记录。 */
function buildErrorResponses(contract) {
  const errors = [
    [400, "BAD_REQUEST", "参数格式、校验或状态前置条件不满足"],
    [401, "UNAUTHORIZED", "没有有效认证会话或服务身份"],
    [403, "FORBIDDEN", "资源动作、数据范围或审批事实不足"],
    [409, "CONFLICT", "幂等键、版本或状态机发生冲突"],
  ];
  if (contract.parameters.some((item) => item.location === "PATH")) {
    errors.splice(3, 0, [404, "NOT_FOUND", "路径标识对应的资源不存在或当前主体不可见"]);
  }
  errors.push([500, "INTERNAL_ERROR", "服务端执行失败；响应不回显敏感异常正文"]);
  return errors.map(([status, code, message]) => ({ status, code, message }));
}

/** 解析 FastAPI 装配函数中的 @app.get/post/websocket 路由。 */
async function parsePythonApiRoutes(repositoryRoot) {
  const sourceRoot = path.join(repositoryRoot, "python-ai-runtime", "src");
  const files = await listFiles(sourceRoot, (file) => file.endsWith(".py"));
  const contracts = [];
  for (const sourceFile of files) {
    const text = await fs.readFile(sourceFile, "utf8");
    const pattern = /@app\.(get|post|put|patch|delete|websocket)\s*\(\s*["']([^"']+)["']/g;
    for (const match of text.matchAll(pattern)) {
      const decorator = match[1].toLowerCase();
      const routePath = match[2];
      const decoratorOpen = text.indexOf("(", match.index);
      const decoratorClose = scanBalanced(text, decoratorOpen, "(", ")");
      const functionSegment = text.slice(decoratorClose + 1, Math.min(text.length, decoratorClose + 5000));
      const functionMatch = /(?:async\s+)?def\s+(\w+)\s*\(/.exec(functionSegment);
      if (!functionMatch) continue;
      const functionStart = decoratorClose + 1 + functionMatch.index;
      const openParen = text.indexOf("(", functionStart);
      const closeParen = scanBalanced(text, openParen, "(", ")");
      const parametersText = text.slice(openParen + 1, closeParen);
      const parameters = parsePythonParameters(parametersText);
      const bodyParameter = parameters.find((item) => item.location === "BODY");
      const functionBodyStart = text.indexOf(":", closeParen);
      const docstring = pythonFunctionDocstring(text, functionBodyStart + 1);
      const transport = decorator === "websocket"
        ? "WEBSOCKET"
        : /stream/.test(routePath) ? "SSE" : "REST";
      const httpMethod = decorator === "websocket" ? "GET" : decorator.toUpperCase();
      const externalPath = routePath.startsWith("/api/")
        ? routePath
        : routePath.startsWith("/agent/") ? `/api${routePath}` : null;
      const relativeSource = path.relative(repositoryRoot, sourceFile).replaceAll("\\", "/");
      contracts.push(finalizeContract({
        module: "python-ai-runtime",
        controller: relativeSource,
        methodName: functionMatch[1],
        sourceFile: relativeSource,
        sourceLine: lineNumberAt(text, match.index),
        transport,
        httpMethod,
        visibility: /\/internal(?:\/|$)/.test(routePath) ? "INTERNAL" : "PUBLIC",
        declaredPaths: [routePath],
        externalPaths: externalPath ? [externalPath] : [],
        purpose: chineseNarrative(docstring) || `${functionMatch[1]} FastAPI 路由，负责处理对应的受治理请求。`,
        parameters,
        requestType: bodyParameter?.javaType ?? null,
        requestSchema: bodyParameter ? { type: bodyParameter.javaType, fields: [] } : null,
        responseType: transport === "WEBSOCKET" ? "RuntimeEventWebSocketFrame" : "FastAPIResponse",
        responseSchema: { type: transport === "WEBSOCKET" ? "RuntimeEventWebSocketFrame" : "FastAPIResponse", fields: [] },
        consumes: [],
        produces: transport === "WEBSOCKET" ? ["application/websocket"] : transport === "SSE" ? ["text/event-stream"] : ["application/json"],
      }));
    }
  }
  return contracts;
}

/** 解析 FastAPI 函数参数；Pydantic 模型默认视为 JSON Body，Request/WebSocket 视为框架对象。 */
function parsePythonParameters(text) {
  return splitTopLevel(text, ",")
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => {
      const match = /^(\w+)\s*:\s*([^=]+?)(?:\s*=\s*(.+))?$/.exec(item);
      if (!match) return { name: item, variableName: item, location: "MODEL", javaType: "Any", required: false, defaultValue: null, description: "FastAPI 框架参数" };
      const [, name, pythonType, defaultExpression] = match;
      const frameworkType = /Request|WebSocket|BackgroundTasks|Response/.test(pythonType);
      const scalarType = /str|int|float|bool|UUID|datetime|Optional/.test(pythonType);
      const location = frameworkType ? "MODEL" : scalarType && defaultExpression ? "QUERY" : "BODY";
      return {
        name,
        variableName: name,
        location,
        javaType: pythonType.trim(),
        required: !defaultExpression || !/None/.test(defaultExpression),
        defaultValue: defaultExpression?.trim() ?? null,
        description: frameworkType ? "FastAPI 注入的框架上下文" : parameterDefaultDescription(name, location),
      };
    });
}

/** 读取 Python 函数体开头的三引号说明。 */
function pythonFunctionDocstring(text, bodyStart) {
  const segment = text.slice(bodyStart, Math.min(text.length, bodyStart + 3000));
  const match = /^\s*(?:"""|''')([\s\S]*?)(?:"""|''')/.exec(segment);
  return match ? normalizeNarrative(match[1]).split("。", 1)[0] + "。" : "";
}

/** 从注解中提取 path/value 字符串；produces/consumes 的 MIME 不会以斜线开头，因而不会误入路径。 */
function annotationPaths(raw) {
  const paths = [...raw.matchAll(/["'](\/[^"']*)["']/g)].map((match) => match[1]);
  return paths.length ? uniqueStrings(paths) : [""];
}

/** 把类路径和方法路径按 Spring 语义拼接。 */
function combinePaths(classPaths, methodPaths) {
  return uniqueStrings(classPaths.flatMap((classPath) => methodPaths.map((methodPath) => normalizePath(`${classPath}/${methodPath}`))));
}

/** 返回注解声明的 HTTP 方法；RequestMapping 未指定 method 时记为 ANY。 */
function annotationHttpMethods(name, raw) {
  if (HTTP_METHOD_BY_ANNOTATION[name]) return [HTTP_METHOD_BY_ANNOTATION[name]];
  const methods = [...raw.matchAll(/RequestMethod\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)/g)].map((match) => match[1]);
  return methods.length ? uniqueStrings(methods) : ["ANY"];
}

/** 判断 Mapping 是否声明 SSE。 */
function annotationProducesStream(raw) {
  return /TEXT_EVENT_STREAM|text\/event-stream/.test(raw);
}

/** 读取 consumes/produces 中的 MIME 声明。 */
function annotationMediaTypes(raw, attribute) {
  const match = new RegExp(`${attribute}\\s*=\\s*([^)]*)`).exec(raw);
  if (!match) return [];
  return uniqueStrings([
    ...match[1].matchAll(/["']([^"']+\/[^"]+)["']/g),
  ].map((item) => item[1]));
}

/** 计算面向外部调用方的 Gateway 路径；内部接口明确返回空，避免误导为公开 API。 */
function externalPathFor(moduleName, declaredPath) {
  const normalized = normalizePath(declaredPath);
  if (/(^|\/)internal(\/|$)/.test(normalized)) return null;
  if (normalized.startsWith("/api/")) return normalized;
  if (moduleName === "permission-admin" && normalized.startsWith("/permissions")) {
    return normalizePath(`/api/permission/${normalized.slice("/permissions".length)}`);
  }
  if (moduleName === "agent-runtime") {
    if (normalized.startsWith("/agent-runtime")) return normalizePath(`/api/agent/${normalized.slice("/agent-runtime".length)}`);
    if (normalized.startsWith("/agent/")) return `/api${normalized}`;
    return normalizePath(`/api/agent/${normalized}`);
  }
  if (moduleName === "data-sync") return normalizePath(`/api/sync/${normalized}`);
  if (moduleName === "task-management") return normalizePath(`/api/task/${normalized}`);
  if (moduleName === "datasource-management") return normalizePath(`/api/datasource/${normalized}`);
  if (moduleName === "data-quality") {
    return normalized.startsWith("/quality") ? `/api${normalized}` : normalizePath(`/api/quality/${normalized}`);
  }
  if (moduleName === "observability") {
    return normalized.startsWith("/observability") ? `/api${normalized}` : normalizePath(`/api/observability/${normalized}`);
  }
  if (moduleName === "gateway") return normalized;
  return null;
}

/** 读取 Mapping 前最近的一段 Javadoc，避免把上一方法的说明串入当前接口。 */
function nearestJavadoc(text, annotationStart, previousBoundary) {
  const start = text.lastIndexOf("/**", annotationStart);
  const end = text.lastIndexOf("*/", annotationStart);
  if (start < 0 || end < start || start < previousBoundary) return "";
  return text.slice(start + 3, end);
}

/** 抽取 Javadoc 的业务说明，删除 HTML、标签和作者模板。 */
function javadocSummary(javadoc) {
  if (!javadoc) return "";
  const withoutParams = javadoc.split(/\n\s*\*?\s*@(?:param|return|throws|author|since)/, 1)[0];
  const normalized = normalizeNarrative(withoutParams);
  if (!normalized || /@Description\s+DataSmart/.test(normalized)) return "";
  const chinese = chineseNarrative(normalized);
  return chinese.length > 500 ? `${chinese.slice(0, 500)}。` : chinese;
}

/** 读取 Javadoc 的 @param 说明，用于参数表。 */
function javadocParamDescriptions(javadoc) {
  const descriptions = new Map();
  for (const match of String(javadoc || "").matchAll(/@param\s+(\w+)\s+([^\n]+)/g)) {
    const description = chineseNarrative(normalizeNarrative(match[2]));
    if (description) descriptions.set(match[1], description);
  }
  return descriptions;
}

/**
 * 从中英混排注释中保留含中文的完整句段。
 *
 * 接口路径、类型和字段名仍按源码保留英文标识；这里只处理面向读者的用途和参数说明。若源码只有英文
 * 说明，调用方会使用明确的中文兜底文案，维护者仍可通过文档中的源码文件和行号阅读原始注释。
 */
function chineseNarrative(text) {
  const normalized = normalizeNarrative(text);
  if (!normalized) return "";
  return (normalized.match(/[^。！？]+[。！？]?/g) || [])
    .map((sentence) => sentence.trim())
    .filter((sentence) => /[\u3400-\u9fff]/.test(sentence))
    .join(" ")
    .trim();
}

/** 为没有 Javadoc @param 的参数提供中性说明。 */
function parameterDefaultDescription(name, location) {
  return {
    PATH: `路径参数 ${name}，用于定位单个资源。`,
    QUERY: `查询参数 ${name}，用于过滤、分页或选择行为。`,
    HEADER: `请求头 ${name}；身份范围头通常由 Gateway 注入，浏览器不得伪造。`,
    BODY: `JSON 请求体 ${name}。`,
    PART: `multipart 表单部分 ${name}。`,
    MODEL: `框架或复合参数 ${name}。`,
  }[location] || `${name} 参数。`;
}

/** 把 Java/Python 注释文本规范化为适合 Word 正文的单段中文说明。 */
function normalizeNarrative(value) {
  return String(value || "")
    .replace(/^\s*\*\s?/gm, "")
    .replace(/<\/?(?:p|ul|ol|li|strong|em|code|pre)[^>]*>/gi, " ")
    .replace(/\{@code\s+([^}]+)}/g, "$1")
    .replace(/\{@link\s+([^}]+)}/g, "$1")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** 将复杂 Java 类型简化为可读形式，但保留泛型结构。 */
function normalizeJavaType(value) {
  return String(value || "Object")
    .replace(/@[\w.]+(?:\([^)]*\))?\s*/g, "")
    .replace(/\?\s+extends\s+/g, "")
    .replace(/\?\s+super\s+/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

/** 从包装类型中提取可能有源码定义的简单类名，优先最内层业务 DTO。 */
function typeCandidates(javaType) {
  return [...String(javaType || "").matchAll(/\b([A-Z][A-Za-z0-9_]*)\b/g)]
    .map((match) => match[1])
    .filter((name) => ![
      "PlatformApiResponse", "PlatformPageResponse", "ResponseEntity", "List", "Set", "Map",
      "Optional", "Page", "Flux", "Mono", "String", "Long", "Integer", "Boolean", "Object",
      "Void", "HttpHeaders", "MultipartFile",
    ].includes(name))
    .reverse();
}

/** 判断字段附近是否显式声明 Nullable。 */
function isNullableField(text, index) {
  const prefix = text.slice(Math.max(0, index - 160), index);
  return /@Nullable|@Null/.test(prefix);
}

/** 扫描配对括号，识别字符串转义，供注解、方法参数和 record 字段共用。 */
function scanBalanced(text, openIndex, openCharacter, closeCharacter) {
  let depth = 0;
  let quote = null;
  let escaped = false;
  for (let index = openIndex; index < text.length; index += 1) {
    const character = text[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === quote) quote = null;
      continue;
    }
    if (character === '"' || character === "'") {
      quote = character;
      continue;
    }
    if (character === openCharacter) depth += 1;
    if (character === closeCharacter) {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  return -1;
}

/** 按顶层分隔符拆分，忽略括号、泛型、数组和字符串内部的逗号。 */
function splitTopLevel(text, separator) {
  const parts = [];
  let start = 0;
  let round = 0;
  let angle = 0;
  let square = 0;
  let curly = 0;
  let quote = null;
  let escaped = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === quote) quote = null;
      continue;
    }
    if (character === '"' || character === "'") quote = character;
    else if (character === "(") round += 1;
    else if (character === ")") round -= 1;
    else if (character === "<") angle += 1;
    else if (character === ">") angle = Math.max(0, angle - 1);
    else if (character === "[") square += 1;
    else if (character === "]") square -= 1;
    else if (character === "{") curly += 1;
    else if (character === "}") curly -= 1;
    else if (character === separator && round === 0 && angle === 0 && square === 0 && curly === 0) {
      parts.push(text.slice(start, index));
      start = index + 1;
    }
  }
  parts.push(text.slice(start));
  return parts;
}

/** 规范化 URL 路径，保留根路径并消除重复斜线。 */
function normalizePath(value) {
  const normalized = `/${String(value || "").replace(/^\/+|\/+$/g, "")}`.replace(/\/{2,}/g, "/");
  return normalized === "/" ? "/" : normalized.replace(/\/$/, "");
}

/** 返回字符串去重后的稳定顺序。 */
function uniqueStrings(values) {
  return [...new Set(values.filter((value) => value !== null && value !== undefined).map(String))];
}

/** 计算字符偏移对应的 1-based 源码行号。 */
function lineNumberAt(text, index) {
  return text.slice(0, index).split("\n").length;
}

/** 正则安全转义。 */
function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
