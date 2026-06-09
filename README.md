# 智慧油田注水开发动态调控系统

## 系统架构

```
                    ┌─────────────────────────────────────────────────────────────────┐
                    │                        前端 Nginx (Gzip压缩)        │
                    │  ┌──────────────┐  ┌──────────────┐  ┌───────────┐  │
                    │  │  well_map.js │  │ well_detail.  │  │  charts.js│  │
                    │  │  (井位图)   │  │    js      │  │           │  │
                    │  └──────────────┘  └──────────────┘  └───────────┘  │
                    └───────────────────────────┬─────────────────────────────────┘
                                            │ HTTP REST API
                                            ▼
                    ┌─────────────────────────────────────────────────────────┐
                    │                SpringBoot 后端                        │
                    │  ┌─────────────────────────────────────────────────┐  │
                    │  │  Actuator + Micrometer + Prometheus 监控        │  │
                    │  └─────────────────────────────────────────────────┘  │
                    │  ┌───────────┐  ┌──────────────┐  ┌───────────────┐  │
                    │  │WellData   │  │Waterflood    │  │Injection      │  │
                    │  │Receiver   │  │Analyzer      │  │Optimizer      │  │
                    │  │(数据接收)│  │(水驱分析)    │  │(调配优化)    │  │
                    │  └─────┬─────┘  └──────┬───────┘  └──────┬────────┘  │
                    │        │               │                  │           │
                    │        └───────────┬───────┴──────────────────┘           │
                    │                    ▲ Spring Events                   │
                    │              ┌─────┴─────┐                             │
                    │              │ AlarmPublisher │                             │
                    │              │  (告警推送)   │                             │
                    │              └──────┬──────┘                             │
                    └─────────────────────┼───────────────────────────────────────┘
                                          │ MQTT (QoS 1)
              ┌───────────────────────────┼───────────────────────────┐
              ▼                         ▼                         ▼
    ┌───────────────────┐       ┌───────────────────┐       ┌───────────────────┐
    │ PostgreSQL +    │       │  EMQX MQTT Broker│       │  DTU 模拟器     │
    │ PostGIS       │       │  (QoS 1, 离线缓存)│       │  (800口井)      │
    │ 空间索引+分区 │       │                   │       │  每日上报       │
    └───────────────────┘       └───────────────────┘       └───────────────────┘
```

## 技术栈

### 后端
- **框架**: Spring Boot 3.2.0 + Spring Data JPA
- **数据库**: PostgreSQL 15 + PostGIS 3.4
- **空间计算**: JTS + Hibernate Spatial
- **消息队列**: Eclipse Paho MQTT
- **优化算法**: Apache Commons Math3 (线性规划)
- **监控**: Spring Boot Actuator + Micrometer + Prometheus
- **事件驱动**: Spring Application Events

### 前端
- **地图**: Leaflet 1.9.4
- **图表**: Chart.js 4.4.0
- **渲染**: Canvas 2D
- **Web服务器**: Nginx 1.25 (Gzip压缩)

### DevOps
- **容器化**: Docker + Docker Compose
- **MQTT Broker**: EMQX 5.3.2
- **监控可视化**: Grafana 10.2.3
- **CI/CD**: 多阶段Docker构建

## 项目结构

```
.
├── backend/                    # SpringBoot 后端
│   ├── src/main/
│   │   ├── java/com/smart/oilfield/
│   │   │   ├── config/         # 配置类
│   │   │   ├── controller/     # REST API
│   │   │   ├── dto/            # 数据传输对象
│   │   │   ├── entity/         # 实体类
│   │   │   ├── event/          # Spring Events
│   │   │   ├── repository/     # 数据访问
│   │   │   └── service/        # 业务逻辑（4个拆分模块）
│   │   └── resources/
│   │       └── application.yml  # 应用配置
│   ├── Dockerfile              # 多阶段构建
│   └── pom.xml
├── database/                   # 数据库脚本
│   ├── init_schema.sql        # 初始化脚本
│   ├── partition.sql          # 自动分区配置
│   └── postgresql.conf        # PostgreSQL配置
├── frontend/                   # 前端
│   ├── index.html
│   ├── css/
│   └── js/
│       ├── app.js
│       ├── well_map.js         # 井位图组件
│       ├── well_detail.js      # 单井详情组件
│       ├── charts.js
│       ├── api.js
│       └── config.js
├── simulator/                # DTU模拟器
│   ├── dtu_simulator_enhanced.py  # 增强版模拟器
│   ├── dtu_simulator.py         # 原始模拟器
│   ├── requirements.txt
│   └── Dockerfile
├── docker/                   # Docker配置
│   ├── emqx/
│   │   └── emqx.conf         # EMQX配置（QoS 1）
│   ├── nginx/
│   │   ├── nginx.conf        # Nginx配置（Gzip）
│   │   └── conf.d/
│   │       └── default.conf
│   ├── prometheus/
│   │   └── prometheus.yml     # Prometheus配置
│   └── grafana/
│       └── provisioning/
│           ├── datasources/
│           └── dashboards/
├── docker-compose.yml          # Docker Compose编排
└── README.md
```

## 快速开始

### 环境要求
- Docker 20.10+
- Docker Compose 2.0+
- 至少 8GB 内存
- 至少 4 CPU 核心

### 一键部署

#### 1. 启动核心服务（后端+数据库+MQTT+前端）

```bash
# 构建并启动所有核心服务
docker-compose up -d --build
```

#### 2. 启动包含监控服务（可选）

```bash
# 启动监控栈（Prometheus + Grafana）
docker-compose --profile monitoring up -d
```

#### 3. 启动DTU模拟器（可选）

```bash
# 启动模拟器，模拟800口井每日上报
docker-compose --profile simulator up -d
```

### 服务访问地址

| 服务 | 地址 | 账号/密码 |
|------|------|----------|
| 前端 | http://localhost | - |
| 后端API | http://localhost/api | - |
| Actuator监控 | http://localhost/api/actuator | - |
| Prometheus | http://localhost:9090 | - |
| Grafana | http://localhost:3000 | admin / admin123 |
| EMQX控制台 | http://localhost:18083 | admin / public |
| 模拟器健康检查 | http://localhost:8000/health | - |

### 停止服务

```bash
# 停止所有服务
docker-compose down

# 停止并清除数据
docker-compose down -v
```

## 后端架构详解

### 模块化拆分（4个核心Service）

#### 1. WellDataReceiver - 数据接收服务
- **职责**: 数据校验、异步批量写入
- **关键特性**:
  - 双BlockingQueue异步队列（注水井/采油井分离）
  - Hibernate JDBC批处理（batch_size=50）
  - HikariCP连接池（max=50, min=10）
  - 发布`WellDataReceivedEvent`事件

```java
@Service
public class WellDataReceiver {
    private final BlockingQueue<WaterInjectionData> injectionQueue = new LinkedBlockingQueue<>(10000);
    
    @PostConstruct
    public void init() {
        batchExecutor.submit(this::processInjectionQueue);
    }
}
```

#### 2. WaterfloodAnalyzer - 水驱分析服务
- **职责**: 水驱特征曲线回归、受效程度分析
- **关键特性**:
  - `@Async`异步监听`WellDataReceivedEvent`
  - 水驱曲线对数回归算法
  - 井间受效程度计算
  - 发布`WaterfloodAnalysisCompletedEvent`

#### 3. InjectionOptimizer - 调配优化服务
- **职责**: 线性规划求解、调配建议生成
- **关键特性**:
  - 自适应求解器（小样本Simplex，大样本对偶分解）
  - 对偶分解法处理300口井大规模问题
  - `@Transactional`事务保障
  - 发布`AllocationOptimizationCompletedEvent`

#### 4. AlarmPublisher - 告警推送服务
- **职责**: 两级告警评估、MQTT推送
- **关键特性**:
  - 实时监听`WellDataReceivedEvent`实时告警检查
  - 监听`AllocationOptimizationCompletedEvent`推送调配建议
  - 定时告警检查（每日凌晨1点）
  - MQTT QoS 1推送 + 离线缓存

### Spring Events事件流

```
数据接收 → WellDataReceivedEvent → WaterfloodAnalyzer
                                      ↓
                              WaterfloodAnalysisCompletedEvent → InjectionOptimizer
                                                                 ↓
                                                    AllocationOptimizationCompletedEvent → AlarmPublisher
                                                                                          ↓
                                                                                    AlarmTriggeredEvent
```

### 监控配置（Actuator + Micrometer

**暴露的监控端点：

| 端点 | 说明 |
|------|------|
| `/api/actuator/health` | 健康检查（含liveness/readiness探针） |
| `/api/actuator/info` | 应用信息 |
| `/api/actuator/metrics` | 指标信息 |
| `/api/actuator/prometheus` | Prometheus指标 |
| `/api/actuator/loggers` | 日志级别管理 |
| `/api/actuator/heapdump` | 堆转储 |
| `/api/actuator/threaddump` | 线程转储 |

**核心指标：
- JVM指标（内存、GC、线程）
- 系统指标（CPU、磁盘）
- 自定义业务指标

## PostgreSQL配置详解

### 空间索引优化

```sql
-- GIST空间索引，填充因子90%
CREATE INDEX idx_wells_geom ON wells USING GIST (geom)
WITH (fillfactor = 90);

-- 表聚类提高空间查询性能
CLUSTER wells USING idx_wells_geom;
```

### 自动分区配置

**分区策略**: 按月份范围分区，自动创建分区：

```sql
-- 自动创建分区函数
CREATE OR REPLACE FUNCTION create_injection_partition(target_date DATE)
RETURNS VOID AS $$
-- 分区命名: water_injection_data_y2024m06
-- 自动创建过去2年+未来1年分区
-- 插入数据前触发器自动创建新区
```

**覆盖索引优化常用查询：

```sql
-- 注水井最新数据查询覆盖索引
CREATE INDEX idx_injection_well_date_include
ON water_injection_data (well_id, report_date DESC)
INCLUDE (water_volume, injection_pressure, water_absorption_index);
```

### PostgreSQL性能参数优化

| 参数 | 值 | 说明 |
|------|-----|------|
| shared_buffers | 1GB | 共享缓冲区 |
| effective_cache_size | 3GB | 优化器估算 |
| work_mem | 4MB | 排序操作内存 |
| maintenance_work_mem | 256MB | 维护操作内存 |
| max_parallel_workers | 8 | 并行查询 |
| log_min_duration_statement | 1s | 慢查询日志 |
| pg_stat_statements | 开启 | SQL统计 |

## EMQX MQTT Broker配置

### QoS 1配置

```hocon
mqtt {
  max_qos_allowed = 2
  qos1_max_pending_msgs = 10000
  session_expiry_interval = 2h
  max_inflight = 32
}
```

### 关键配置项：

| 配置项 | 值 | 说明 |
|--------|-----|------|
| max_connections | 100000 | 最大连接数 |
| QoS 1 pending | 10000 | QoS 1消息队列长度 |
| 会话过期 | 2小时 | 离线消息保留时间 |
| 飞行窗口 | 32 | 并发消息数 |
| 保留消息 | 100000 | 最大保留消息数 |

## Nginx配置（Gzip压缩）

```nginx
gzip on;
gzip_comp_level 6;
gzip_min_length 1024;
gzip_types text/plain text/css text/javascript
             application/javascript application/json
             application/xml image/svg+xml;
```

**压缩效果：
- HTML/CSS/JS压缩比约70%
- JSON API响应压缩比约60%
- 支持缓存静态资源7天

## DTU模拟器使用说明

### 增强版模拟器特性

- ✅ 支持300口注水井 + 500口采油井
- ✅ 可配置含水率上升趋势
- ✅ 每日定时上报（默认8点）
- ✅ MQTT QoS 1发布
- ✅ HTTP健康检查和指标接口
- ✅ 3种运行模式

### 环境变量配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| MQTT_BROKER | tcp://emqx:1883 | MQTT Broker地址 |
| MQTT_PORT | 1883 | MQTT端口 |
| MQTT_USERNAME | admin | MQTT用户名 |
| MQTT_PASSWORD | public | MQTT密码 |
| INJECTION_WELL_COUNT | 300 | 注水井数量 |
| PRODUCTION_WELL_COUNT | 500 | 采油井数量 |
| WATER_CUT_RISE_TREND | 0.02 | 含水率日上升趋势（%/天 |
| REPORT_SCHEDULE | daily | 上报模式 |
| REPORT_HOUR | 8 | 每日上报时间（小时） |

### 运行模式

#### 1. Docker Compose方式启动模拟器

```bash
# 启动模拟器
docker-compose --profile simulator up -d

# 查看模拟器日志
docker logs smart-oilfield-dtu-simulator
```

#### 2. 直接运行Python脚本

```bash
cd simulator
pip install -r requirements.txt

# 补录历史数据（回溯90天）
python dtu_simulator_enhanced.py --mode backfill --start-date 2024-01-01 --end-date 2024-03-31 --speed 10.0

# 每日定时上报
python dtu_simulator_enhanced.py --mode daily

# 连续上报（每小时一次）
python dtu_simulator_enhanced.py --mode continuous --interval 3600
```

### 模拟器HTTP接口

| 接口 | 说明 |
|------|------|
| `GET /health | 健康检查 |
| `GET /metrics` | Prometheus指标 |

### 健康检查响应示例

```json
{
  "status": "healthy",
  "uptime_seconds": 3600,
  "total_published": 800,
  "total_failed": 0,
  "last_report_time": "2024-06-09T08:00:00",
  "injection_wells": 300,
  "production_wells": 500,
  "water_cut_rise_trend": 0.02
}
```

## API接口文档

### 核心指标接口

```bash
# 获取区块核心指标
GET /api/summary/core?blockName=东区

# 获取井列表
GET /api/wells?blockName=东区

# 获取单井90天趋势
GET /api/wells/{wellId}/trend?days=90

# 获取注采关系
GET /api/relations?blockName=东区

# 获取最新调配建议
GET /api/allocation/latest

# 获取未确认告警
GET /api/alarms/unacknowledged
```

## 部署架构说明

### 网络架构

```
                    Internet
                        │
                        ▼
              ┌───────────────────┐
              │   Nginx (80)      │
              │  反向代理+Gzip   │
              └─────────┬───────────┘
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │ 前端    │  │ 后端API  │  │ Actuator │
  │ 静态资源 │  │ (8080)   │  │ 监控端点 │
  └──────────┘  └──────┬───┘  └──────────┘
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │PostgreSQL│  │  EMQX     │  │Prometheus│
  │ (5432)  │  │ (1883)   │  │ (9090)   │
  └──────────┘  └──────────┘  └──────────┘
```

### 数据流向

```
DTU模拟器 → MQTT (QoS 1) → WellDataReceiver → 批量写入PostgreSQL
                                                      ↓
                                          Spring Events → WaterfloodAnalyzer
                                                      ↓
                                          Spring Events → InjectionOptimizer
                                                      ↓
                                          Spring Events → AlarmPublisher → MQTT推送 → 调度大屏
```

## 性能优化总结

| 优化项 | 优化前 | 优化后 |
|--------|--------|--------|
| HikariCP连接池 | 10 | 50 |
| 写入方式 | 单条 | 批量50条 |
| LP求解算法 | Simplex O(n³) | 对偶分解 O(8×40³) |
| MQTT消息 | 无限堆积 | 5分钟过期 + 离线队列 |
| 前端资源 | 无压缩 | Gzip压缩60%+ |
| 数据表 | 单表 | 按月自动分区 |
| 空间查询 | 普通索引 | GIST空间索引+聚类 |
| 模块耦合 | 高耦合 | Spring Events事件驱动 |

## 故障排查

### 查看服务状态

```bash
# 查看所有服务状态
docker-compose ps

# 查看后端日志
docker logs smart-oilfield-backend --tail 100

# 查看数据库日志
docker logs smart-oilfield-postgres

# 查看EMQX日志
docker logs smart-oilfield-emqx

# 查看模拟器日志
docker logs smart-oilfield-dtu-simulator
```

### 常见问题

**Q: 后端启动失败，数据库连接错误？
A: 检查PostgreSQL容器是否健康，数据库初始化是否完成。

**Q: MQTT消息推送失败？
A: 检查EMQX连接状态，查看`/api/actuator/health。

**Q: 模拟器不发送数据？
A: 检查模拟器健康检查`curl localhost:8000/health。

**Q: 前端访问慢？
A: 检查Nginx Gzip压缩是否生效，查看浏览器开发者工具。

## 许可证

MIT License
