# SpecUnfold 使用说明

本安装包适用于 Windows x64，已包含 Java 和 Node.js 运行环境。
使用者不需要安装 JDK、Maven、Node.js 或下载源码。

## 安装软件

运行安装程序，选择软件目录，例如 `F:\Apps\SpecUnfold`。
安装完成后，从开始菜单打开 SpecUnfold。安装程序只安装软件，不自动修改代码项目。

## 接入项目

在 SpecUnfold 窗口中选择已有项目目录，点击“安装到所选项目”。
第一版安装内置 example（示例）规范，添加 `.agents/profiles/example`、
`.agents/harness` 和 `.agents/skills/specunfold-router`，并向该项目的
`AGENTS.md`、`CLAUDE.md` 写入受管入口。入口外的原有指令保留。
已存在但内容不同的规范文件会阻止安装，不自动覆盖。
这一步只安装规范和入口，不创建工作流执行或替用户确认工作流。

## 连接助手

点击“复制连接配置”，在支持 MCP（模型上下文协议）的助手或配置管理工具中添加服务。
完整配置中的 `mcpServers.specunfold` 是服务配置；不同工具需要填写的层级可能不同。
启动程序选择软件安装目录下的 `SpecUnfold-server.exe`，参数留空。
该服务通过标准输入输出通信，无须监听网络端口。
项目由助手工作区、`SPECUNFOLD_WORKSPACE_ROOT` 或调用参数 `workspaceRoot` 指定。
本程序提供配置供复制，不自动修改助手的全局连接设置。

## 命令行安装与启动

如需在终端安装项目，可使用服务入口并覆盖其默认参数：

```powershell
& 'F:\Apps\SpecUnfold\SpecUnfold-server.exe' install --workspace 'F:\代码项目' --profile example
```

直接运行 `SpecUnfold-server.exe` 会启动协议服务，等待助手连接。
不要把它当成项目安装窗口；需要图形界面时打开 `SpecUnfold.exe`。

## 升级与卸载

安装新版软件前关闭 SpecUnfold 窗口，并在助手中停止服务，释放占用的程序文件。
通过系统的“已安装的应用”卸载 SpecUnfold。
卸载移除软件，不删除其他项目目录中的规范、指令或工作流记录。

当前为第一版安装交付，不包含自动更新。自定义规范声明的其他脚本语言仍需自行提供。
