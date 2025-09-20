````markdown
# WildFly 热部署（Exploded）完整配置 + IDEA(Community) 脚本/调试手顺（无 robocopy 版）

> 目标：使用 **Exploded + Deployment Scanner** 实现“保存→自动热部署”；DB2 驱动以 **模块** 方式避免 Weld/OOM；在 **IntelliJ IDEA Community** 中一键启动/远程调试/实时看日志。  
> 平台：Windows 10/11，WildFly 23.x（例：`C:\wildfly-23.0.2.Final`），项目：`C:\livalit-fs\idea\livalit-app-lvdb`，应用名：`livalit-app-lvdb-1.0.0`。

---

## 0. 前提

- Windows 开启 **开发者模式**（或使用**管理员**命令行）以允许 `mklink`。
- 你的 exploded 产物目录将是：  
  `C:\livalit-fs\idea\livalit-app-lvdb\target\livalit-app-lvdb-1.0.0`

---

## 1) 一次性“环境归零”

### 1.1 停 WildFly

```powershell
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 --commands=":shutdown" 2>$null
````

或任务管理器结束 Java/WildFly 进程。

### 1.2 清空 `deployments` 下旧痕迹

路径：`C:\wildfly-23.0.2.Final\standalone\deployments`
删除以下（文件/目录/链接均删）：

* `livalit-app-lvdb-1.0.0.war`
* `livalit-app-lvdb-1.0.0.war.*`（`.dodeploy/.deployed/.failed/.isdeploying/.skipdeploy/.undeployed`）

### 1.3 清管理模型“受管部署”

```powershell
# 列出受管部署
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 --commands=":read-children-names(child-type=deployment)"

# 若有同名项，撤销并删除
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 --commands="undeploy livalit-app-lvdb-1.0.0.war"
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 --commands="/deployment=livalit-app-lvdb-1.0.0.war:remove()"
```

> 目的：**管理模型里不保留本应用**，后续靠扫描器 + 文件系统链接热部署。

---

## 2) DB2 驱动改为 WildFly 模块（避免被当“部署”）

### 2.1 放 JAR

```
C:\wildfly-23.0.2.Final\modules\com\ibm\db2\main\jcc-11.1.4.4.jar
```

### 2.2 写 `module.xml`（同目录）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<module xmlns="urn:jboss:module:1.9" name="com.ibm.db2">
  <resources>
    <resource-root path="jcc-11.1.4.4.jar"/>
  </resources>
  <dependencies>
    <module name="javax.api"/>
    <module name="javax.transaction.api"/>
  </dependencies>
</module>
```

### 2.3 注册 JDBC 驱动（一次性）

```powershell
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 `
  --commands="/subsystem=datasources/jdbc-driver=db2:add(driver-name=db2,driver-module-name=com.ibm.db2,driver-class-name=com.ibm.db2.jcc.DB2Driver)"
```

### 2.4 数据源与构建

* `standalone.xml` 的数据源使用 `<driver>db2</driver>`
* `pom.xml` 将 DB2 驱动设为 `provided`（不要打进 WAR）

---

## 3) 开启 Deployment Scanner（Exploded）

```powershell
# 开启扫描器
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 `
  --commands="/subsystem=deployment-scanner/scanner=default:write-attribute(name=scan-enabled,value=true)"

# 扫描周期（ms）
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 `
  --commands="/subsystem=deployment-scanner/scanner=default:write-attribute(name=scan-interval,value=2000)"

# 支持 exploded
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 `
  --commands="/subsystem=deployment-scanner/scanner=default:write-attribute(name=auto-deploy-exploded,value=true)"

# 复查
& "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 `
  --commands="/subsystem=deployment-scanner/scanner=default:read-resource"
```

---

## 4) 用“链接”把 exploded 映射到 `deployments`（**不用 robocopy**）

### 4.1 源目录（确保存在且会随编译更新）

```
C:\livalit-fs\idea\livalit-app-lvdb\target\livalit-app-lvdb-1.0.0
```

### 4.2 在 `deployments` 创建“目录式 WAR 名称”的链接

目标链接：

```
C:\wildfly-23.0.2.Final\standalone\deployments\livalit-app-lvdb-1.0.0.war
```

**优先符号链接（/D）**：

```cmd
:: 管理员或开发者模式
mklink /D "C:\wildfly-23.0.2.Final\standalone\deployments\livalit-app-lvdb-1.0.0.war" "C:\livalit-fs\idea\livalit-app-lvdb\target\livalit-app-lvdb-1.0.0"
```

**若 /D 不被允许，则用目录联接（/J）**：

```cmd
mklink /J "C:\wildfly-23.0.2.Final\standalone\deployments\livalit-app-lvdb-1.0.0.war" "C:\livalit-fs\idea\livalit-app-lvdb\target\livalit-app-lvdb-1.0.0"
```

### 4.3 触发首次部署

```cmd
type NUL > "C:\wildfly-23.0.2.Final\standalone\deployments\livalit-app-lvdb-1.0.0.war.dodeploy"
```

> 之后只要 **IDEA/Maven 写入 target**，扫描器会自动拾取并热部署。

---

## 5) 启动 WildFly（含远程调试 & JVM 内存）

### 5.1 启动示例（命令行）

```cmd
"%WILDFLY_HOME%\bin\standalone.bat" ^
  --debug 8787 -c standalone.xml -b 0.0.0.0 -bmanagement 127.0.0.1 -Djboss.http.port=8080 ^
  -Dfile.encoding=UTF-8
```

**JVM 内存（编辑 `standalone.conf.bat` 或环境变量 `JAVA_OPTS`）**：

```bat
set "JAVA_OPTS=%JAVA_OPTS% -Xms512m -Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
```

---

## 6) IntelliJ IDEA Community 配置

### 6.1 Artifacts 输出到 `target`

* `File → Project Structure… → Artifacts`

    * 新建/选中 **Web Application: Exploded**：`livalit-app-lvdb-1.0.0`
    * **Output directory**：
      `C:\livalit-fs\idea\livalit-app-lvdb\target\livalit-app-lvdb-1.0.0`
    * 内容包含：`Module Output`（编译类→`WEB-INF/classes`）、`src/main/resources`、`src/main/webapp`
    * 勾选/启用 **Build on make**
* `Settings → Build, Execution, Deployment → Compiler`：勾 **Build project automatically**
* `Settings → Advanced Settings`：勾 **Allow auto-make to start even if the application is running**
* `Settings → Appearance & Behavior → System Settings`：勾 **Auto save** 相关选项

> 也可用 Maven 产出 exploded。关键是 **输出路径** 与链接源一致。

### 6.2 在 IDEA 一键启动 WildFly（External Tools）

**Start WildFly (Debug)**

* `Settings → Tools → External Tools → +`

    * **Name**：Start WildFly (Debug)
    * **Program**：`C:\Windows\System32\cmd.exe`
    * **Arguments**：

      ```
      /c ""%WILDFLY_HOME%\bin\standalone.bat" --debug 8787 -c standalone.xml -b 0.0.0.0 -bmanagement 127.0.0.1 -Djboss.http.port=8080"
      ```
    * **Working directory**：`$Env:WILDFLY_HOME$\bin`
    * 勾选 **Open console**

> 需要的话，再新增一个 External Tool“**Touch dodeploy**”，用于显式触发：
>
> ```
> /c type NUL > "C:\wildfly-23.0.2.Final\standalone\deployments\livalit-app-lvdb-1.0.0.war.dodeploy"
> ```

### 6.3 Remote JVM Debug（Attach）

* `Run → Edit Configurations… → + → Remote JVM Debug`

    * **Host**：`localhost`
    * **Port**：`8787`
* 启动 WildFly 后，运行该配置即可附加断点调试。

---

## 7) 实时查看 `server.log`

最简方式（单独 PowerShell 窗口）：

```powershell
Get-Content "$env:WILDFLY_HOME\standalone\log\server.log" -Wait -Tail 200
```

> 也可使用彩色/JSON/SQL 高亮版日志脚本（如已有）。

---

## 8) 故障排查

### 8.1 热部署没有反应

* `deployments` 下是否是**目录式 WAR 名称**：
  `...\deployments\livalit-app-lvdb-1.0.0.war` **应为目录链接**（或目录），不是 `.war` 文件。
* IDEA 是否真的把产物写到 `target\...`？（看文件时间戳/内容）
* 扫描器状态：

  ```powershell
  & "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 --commands="/subsystem=deployment-scanner/scanner=default:read-resource"
  ```

    * `scan-enabled = true`
    * `auto-deploy-exploded = true`
    * `scan-interval = 2000`（或你设定的值）
* 管理模型里是否**仍有**同名受管部署（应当没有）：

  ```powershell
  & "$env:WILDFLY_HOME\bin\jboss-cli.bat" -c --controller=127.0.0.1:9990 --commands=":read-children-names(child-type=deployment)"
  ```

### 8.2 出现 `.failed`

* 删除对应 `*.failed`
* 再次触发：`type NUL > *.dodeploy`
* 查看 `server.log` 精确错误（编译失败、类冲突、Weld、资源缺失等）

### 8.3 OOM / WeldStartService / GC overhead limit exceeded

* DB2 驱动**必须为模块**，不要在 WAR 内
* 提高 JVM 内存（`-Xmx`、`-XX:MaxMetaspaceSize`）
* 启动时不要做大体量缓存预热；用开关控制、懒加载、分页/流式

### 8.4 无法创建链接

* 用 **管理员/开发者模式** 执行 `mklink /D`
* 不行就用 **junction**：`mklink /J`
* 实在被策略限制，才考虑 robocopy（本手顺不使用）

### 8.5 需要“完全重置”

1. 停 WildFly
2. 删除 `deployments` 下链接目录 + `.war.*` 标记
3. 清管理模型受管部署
4. 重新创建链接 + `*.dodeploy`

---

## 9) 日常开发最小步骤（Checklist）

1. IDEA 打开 **Auto-make** 与 **Auto-save**
2. Artifacts（或 Maven）产出到：`target\livalit-app-lvdb-1.0.0`
3. `deployments` 下建好链接：`...\livalit-app-lvdb-1.0.0.war → target\...`
4. 首次 `type NUL > *.dodeploy`
5. 启 WildFly（`--debug 8787`），用 Remote Debug 附加
6. **保存/编译** → 文件落到 `target` → 扫描器自动热部署 → 断点命中

---

### 附：一键创建链接与触发（可选 PowerShell）

```powershell
$src = "C:\livalit-fs\idea\livalit-app-lvdb\target\livalit-app-lvdb-1.0.0"
$dst = "C:\wildfly-23.0.2.Final\standalone\deployments\livalit-app-lvdb-1.0.0.war"

if (-not (Test-Path $src)) { Write-Error "Exploded not found: $src"; exit 1 }

# 尝试删除旧目标（目录/链接）
if (Test-Path $dst) { cmd /c rmdir "$dst" | Out-Null }

# 优先符号链接（需要管理员/开发者模式）；失败则退回 junction
cmd /c mklink /D "$dst" "$src" 2>$null
if (-not (Test-Path $dst)) {
  cmd /c mklink /J "$dst" "$src" 2>$null
}
if (-not (Test-Path $dst)) { Write-Error "mklink failed."; exit 1 }

# 触发部署
New-Item -ItemType File -Force -Path "$dst.dodeploy" | Out-Null
Write-Host "Linked and triggered: $dst"
```

> 运行一次后，后续就只需**保存代码**，IDEA 将产物写入 `target`，WildFly 扫描器会自动热部署。

```
```