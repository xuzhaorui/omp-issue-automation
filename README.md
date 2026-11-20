# OMP Issue Automation

本地 Java 21 命令行程序：按登记项目创建 GitHub issue，扫描待处理事项，以独立工作区和 OMP 会话实现、运行测试并提出 PR。PR 必须由人审核和合并，程序不会自动合并。调度不需要 Web 服务。

## 准备

- Java 21、Maven、Git、GitHub CLI `gh` 和支持 JSON 输出、显式扩展的 OMP CLI。
- 在运行用户下先完成 `gh auth login` 和 OMP 模型登录；为目标仓库授予必要的 issue、分支和 PR 权限。凭据由各自的工具管理，不写进本项目、示例配置或调度规则。
- 已克隆的可信本地 Git 仓库及其测试工具；macOS 使用 `sandbox-exec`，Linux 需要可用的沙箱工具。隔离或测试不可用时任务应失败，而不是绕过检查。

复制 `config/projects.example.yml` 到仓库外的私有文件，例如 `~/.config/omp-issue/projects.yml`，修改其中路径和 `owner/repo`。每个项目路径必须已存在；`aliases` 可用于 `--project`，名称与别名不能冲突。`tests` 是可信命令的 argv 数组，例如 `[["mvn", "verify"]]`，不是 shell 字符串；不配置时仅能按可信项目基础版本探测已支持的测试。没有可用测试时不会当作通过。`self: true` 只给本程序源码项目设置一次，且更新源分支须为 `main`。

```sh
mkdir -p "$HOME/.config/omp-issue"
cp config/projects.example.yml "$HOME/.config/omp-issue/projects.yml"
chmod 600 "$HOME/.config/omp-issue/projects.yml"
# 用编辑器修改其中的仓库、目录与测试命令
```

`concurrency` 控制同时处理的任务数，`maxAttempts`、`retrySeconds` 控制重试次数和间隔，`pollSeconds` 控制 daemon 的轮询间隔。`omp.timeoutSeconds` 是单次运行超时，`omp.maxCost` 是模型运行预算设置；模型未报告费用时费用为未知，不可视为零或承诺总费用上限。

## 构建、安装与使用

在源码根目录运行（下文 `CONFIG` 为私有配置文件绝对路径）：

```sh
mvn verify
CONFIG="$HOME/.config/omp-issue/projects.yml"
./bin/omp-issue --config "$CONFIG" doctor
./bin/omp-issue --config "$CONFIG" install --jar target/omp-issue-automation.jar
```

源码启动器运行 `target/omp-issue-automation.jar`；安装后使用配置的 `stateDir/bin/omp-issue`，每次启动时读取 `stateDir/current/app.jar`。下例假设示例中的默认状态目录：

```sh
CLI="$HOME/.local/state/omp-issue-automation/bin/omp-issue"
"$CLI" --config "$CONFIG" create --project example --requirement '添加搜索功能' --acceptance '输入关键字后显示匹配结果'
"$CLI" --config "$CONFIG" scan
"$CLI" --config "$CONFIG" status
"$CLI" --config "$CONFIG" retry --project example --issue 123
"$CLI" --config "$CONFIG" doctor
```

省略 `--acceptance` 时会调用无工具权限的 OMP 草拟对应需求的验收条件；显式提供则不需要草拟。`scan` 获取全局锁，处理当前可用任务后退出；多次启动不会并发执行相同调度。也可运行 `"$CLI" --config "$CONFIG" daemon`，使用配置的 `pollSeconds` 持续轮询；不要同时运行多个调度器。

## 接入现有 agent-autoloop

优先复用现有在线 agent-autoloop，不替换其已有规则、Agent、通道或通信配置。在 `rules.json` 的现有 `agents` 对象中增加专用 `use: command` Agent，并在 `rules` 数组增加一条每分钟规则。将以下两个绝对路径、worker 名称换成自己的值；配置文件路径要作为全局参数放在 `scan` 之前。仅展示要合并的片段，不是完整规则文件：

```json
{
  "agents": {
    "omp-issue-scan": {
      "name": "OMP issue 扫描",
      "worker": "YOUR_WORKER",
      "use": "command",
      "command": ["/ABSOLUTE/STATE/bin/omp-issue", "--config", "/ABSOLUTE/CONFIG/projects.yml", "scan"],
      "timeout": 180
    }
  },
  "rules": [
    {
      "name": "OMP issue 每分钟扫描",
      "when": {"cron": "* * * * *"},
      "to": "omp-issue-scan",
      "cwd": "/ABSOLUTE/STATE",
      "maxActive": 1,
      "prompt": "扫描并处理已登记项目的 issue"
    }
  ]
}
```

先将修改后的完整规则另存草稿，用 `agent-autoloop check 草稿文件` 校验，再合并到现有规则并运行 `agent-autoloop check`。规则热更新生效；通过 `agent-autoloop status` 查看状态。cron 以分钟为粒度；需要其他 `pollSeconds` 时改用 daemon，不要同时启用该 cron。

## 安全更新与恢复

在 GitHub 人工审核并合并本项目的 PR，将注册为 `self: true` 的源码工作树切换到干净的 `main`，然后运行：

```sh
"$CLI" --config "$CONFIG" update --source "$HOME/code/omp-issue-automation"
```

更新验证 origin 为登记的 GitHub 仓库，获取 `origin/main` 并要求本地 HEAD 与其完全一致，执行 `mvn clean verify` 后才安装。更新和安装均持有与扫描相同的系统锁；调度执行中会拒绝操作，稍后重试。新 JAR 按 SHA-256 放入 `stateDir/releases/<sha256>/app.jar`，通过原子替换 `current` 符号链接切换，不修改正在运行的 JAR。构建或验证失败时保留原版本；不会自动合并或拉取本地分支。更新期间不要同时手动修改源码或状态目录。

任务记录和日志位于 `stateDir/jobs/<owner>/<repo>/<issue>/`，包含工作树及运行记录；它们可能含私有代码、issue 或模型输出，需限制访问并备份。远端 GitHub 认领 ref 是持久的，不会过期或自动删除；不能仅因时间过去就假定任务已无人处理。若本机状态丢失或出现孤儿认领，先停止所有调度并确认没有其他机器正在处理，恢复对应的本地状态备份，再核对 GitHub 认领、分支及 PR。无法恢复时由仓库维护者人工核实和处理孤儿认领后再重试；切勿盲目删除认领造成重复工作。

## 信任边界

项目注册表、源码、构建工具和测试命令由管理员信任；issue、PR 内容和模型输出不可信。模型在任务独立工作区中使用受限工具；测试通过操作系统沙箱运行，但沙箱并非虚拟机，也不能保证抵御内核漏洞、恶意可信工具或所有数据泄露。使用最小权限的专用账号，勿将秘密放在仓库、配置或模型可访问的位置；审查生成的改动和 PR，勿将本工具用于不能承受该风险的环境。详见 [SECURITY.md](SECURITY.md)。

## 许可证

[MIT](LICENSE)。
