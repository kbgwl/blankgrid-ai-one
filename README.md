# 空白格 · AI 控制台(BlankGrid AI One)

> 一人,一格,一军。每个空白格里,住着一位 AI。
> 无锡空白格人工智能科技有限公司

AI 即一家公司:创始人只下达目标,CEO Agent 统观全局并把目标拆成部门工作流,部门负责人拆成交付物,团队执行、评审不过就返工,合格成果自动归档。组织架构即界面,每个岗位是一枚发光的格子,实时看到谁在做什么、产出如何。

## 环境准备(首次一次)

仓库不含 `tools/`(数百 MB,已被 `.gitignore` 排除),本地工具链需自己放好:

```
tools/
  jdk-17/                  Temurin JDK 17 (Adoptium)
  apache-maven-3.9.9/
  maven-settings.xml       把中央仓库指向阿里云镜像
  env.bat / env.sh         把上面两者临时注入 PATH,不动系统环境
```

- JDK:https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/ (取 `OpenJDK17U-jdk_x64_windows_hotspot_*.zip`)
- Maven:https://mirrors.huaweicloud.com/apache/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip

解压后确认 `tools\jdk-17\bin\java.exe` 与 `tools\apache-maven-3.9.9\bin\mvn.cmd` 都在,脚本会自动接管;目录名不同则改 `env.bat` 里的两处路径。Node 22 需系统已安装(`tools\node` 存在时也会优先用)。

## 快速启动

**开发模式**(前后端热更新,改代码用):双击 `start.bat`,浏览器自动打开 http://localhost:5180

**生产模式**(单进程,上线/日常使用用):
1. 双击 `build-prod.bat` 构建一次(前端 dist + 后端 jar)
2. 之后双击 `start-prod.bat`,浏览器打开 http://localhost:8090

> 生产模式数据目录为项目根的 `data/`;开发模式为 `backend/data/`。两边数据不互通,建议日常固定用一种。

手动启动:

```bat
:: 后端(8090) —— 先 call 环境脚本,mvn 才会在 PATH 里
call tools\env.bat
cd backend
mvn spring-boot:run

:: 前端(5180),另开一个窗口
cd frontend
npm run dev
```

## 第一次使用

1. 打开 **设置** → 填写大模型服务商(OpenAI 兼容协议,兼容智谱 GLM / OpenAI / DeepSeek 等):
   - Base URL,如 `https://open.bigmodel.cn/api/paas/v4`
   - API Key、默认模型(如 `glm-4.6`)
   - 按需设定「质量与目标引擎」的评审通过分、单件返工轮次与自动拆解
2. 打开 **目标** 页,写下一个公司目标(可补充背景、成功标准、截止时间与 token 预算)。
3. CEO 自动拆解 → 部门负责人拆交付物 → 团队执行 → 评审/返工 → 成果入库;在项目详情里看进度与每条交付,在 **成果** 页检索与下载。
4. 如果项目页出现「需要你补」的面板:写清那些公司查不到的事实(功能清单、真实数据、价格),提交后受阻的交付会立刻重新排队。
5. 想拿到可直接使用的文件:在 **设置 → 成果落盘** 指定目录,之后的成果会自动写成 md 文件;也可随时在项目页点「存到目录」。

## 功能

- **目标生产线**:目标 → 部门工作流 → 交付物 → 执行 → 评审 → 返工 → 成果归档;支持暂停/恢复、单件重试、完成后再返工、整项目 ZIP 导出或直接落盘。
- **缺料上报**:CEO / 部门负责人 / 评审遇到「必须核实的外部事实」(产品功能名、真实客户数、价格、资质)时,不再硬编,而是列进目标的**待补清单**;你在项目页填一次事实,后续拆解、执行、评审全程以它为准,受阻交付自动重新排队。可假设的(文风、篇幅、结构)仍按假设推进并写进 brief。
- **落盘目录**:成果不只存数据库。设置页可指定目录(留空 = 程序目录下 `exports/`),通过评审的成果自动写成 `README.md + plan.md + manifest.json + artifacts/*.md`,目标文件夹形如 `001-目标标题/`;也可在成果库、项目页手动落盘单份。
- **依赖传导**:上游交付受阻时,下游进入「等上游」而不是带着空上下文硬写,上游恢复后自动重新排队。
- **指挥台**:写下目标即开工;目标卡展示部门、进度、成果数、评审均分与 token 消耗。
- **组织架构**:CEO 统观全局、部门负责人、执行岗位三层;每个部门负责人置顶并标注,支持新建部门、添加岗位、调整层级/部门/汇报关系,新部门自动补齐负责人。执行中呼吸光晕、连线流光、出错赤色脉冲、完成涟漪。
- **任务系统**:任务按岗位排队执行,流式输出实时显示;任务可附带其他岗位的产出作为输入,实现协作(如产品经理的 PRD → 开发工程师)。
- **看板**:顶部状态条(在跑 / 待办 / 待处理 / 成果 / token)+ 在办目标进度 + 等你拍板 + 待处理/执行中/完成/失败四列任务流 + 实时动态。
- **成果库**:按状态/项目/关键词检索,查看评审意见与 Markdown 正文,单篇下载、存到落盘目录或随项目打包导出。
- **设置**:多 LLM 服务商配置(密钥只存本机 `settings.json`,接口返回自动掩码)、公司质量准则、落盘目录与自动落盘、访问口令、自动备份。

## 技术栈

- 后端:Spring Boot 3 + JPA + H2(本地文件库 `backend/data/`)+ WebSocket(STOMP)
- 前端:Vue 3 + Vite + Element Plus + SVG 自绘架构图
- 工具链:JDK 17 与 Maven 3.9.9 随项目放在 `tools/`,由 `env.bat` 临时注入 PATH,不污染系统;Node 22 用系统安装

## 上云预留

- 配置全部外置(`application.yml` / 环境变量),API Key 不入库
- H2 → MySQL 只改 `spring.datasource.url`
- 前端 `npm run build` 产出 `dist/` 可静态部署,后端可独立部署
