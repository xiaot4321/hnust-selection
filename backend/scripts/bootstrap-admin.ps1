param(
    # 按业务方已确认的登录标识预填；若要调整，可在执行脚本时显式传参。
    [string]$LoginIdentifier = "admin",
    # 业务方提供的学院正式全称。
    [string]$CollegeName = "计算机科学与工程学院"
)

$ErrorActionPreference = "Stop"
$pomPath = Join-Path $PSScriptRoot "..\pom.xml"

# 初始化开关只传给这一次独立 Java 进程，常规应用启动不启用 Bootstrap Runner。
# 未传学院代码时，由 Service 生成以 TMP- 开头的随机临时代码，并在结果中明示。
$applicationArguments = @(
    "--app.bootstrap.admin-enabled=true"
    "--app.bootstrap.admin-login-identifier=$LoginIdentifier"
    "--app.bootstrap.admin-college-name=$CollegeName"
    "--spring.main.web-application-type=none"
)

# 先完整执行编译、测试和可执行 JAR 打包；构建失败时绝不启动初始化进程。
& mvn -f $pomPath package
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

# 直接通过 java -jar 传参，避免 Maven 插件对逗号、空格和 Unicode 参数的二次拆分。
# --spring.main.web-application-type=none 在 Spring 启动最早阶段生效，不会创建 HTTP 监听端口。
$jarPath = Join-Path (Split-Path $pomPath -Parent) "target\selection-backend-0.1.0-SNAPSHOT.jar"
& java -jar $jarPath @applicationArguments
exit $LASTEXITCODE
