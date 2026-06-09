-- ============================================
-- PostgreSQL 自动分区配置
-- 按日期范围对生产数据表进行分区
-- ============================================

-- ============================================
-- 1. 注水井数据按月份分区
-- ============================================
CREATE OR REPLACE FUNCTION create_injection_partition(target_date DATE)
RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    start_date := date_trunc('month', target_date)::DATE;
    end_date := (start_date + INTERVAL '1 month')::DATE;
    partition_name := 'water_injection_data_y' || to_char(start_date, 'YYYY') || 'm' || to_char(start_date, 'MM');

    IF NOT EXISTS (
        SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF water_injection_data
             FOR VALUES FROM (%L) TO (%L)
             PARTITION BY RANGE (report_date)',
            partition_name, start_date, end_date
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I (report_date DESC)',
            partition_name || '_date_idx', partition_name
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I (well_id, report_date DESC)',
            partition_name || '_well_date_idx', partition_name
        );

        RAISE NOTICE 'Created partition: %', partition_name;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- 2. 采油井数据按月份分区
-- ============================================
CREATE OR REPLACE FUNCTION create_production_partition(target_date DATE)
RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    start_date := date_trunc('month', target_date)::DATE;
    end_date := (start_date + INTERVAL '1 month')::DATE;
    partition_name := 'production_data_y' || to_char(start_date, 'YYYY') || 'm' || to_char(start_date, 'MM');

    IF NOT EXISTS (
        SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF production_data
             FOR VALUES FROM (%L) TO (%L)
             PARTITION BY RANGE (report_date)',
            partition_name, start_date, end_date
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I (report_date DESC)',
            partition_name || '_date_idx', partition_name
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I (well_id, report_date DESC)',
            partition_name || '_well_date_idx', partition_name
        );

        RAISE NOTICE 'Created partition: %', partition_name;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- 3. 自动创建未来12个月的分区
-- ============================================
CREATE OR REPLACE FUNCTION create_future_partitions()
RETURNS VOID AS $$
DECLARE
    current_date DATE;
    target_date DATE;
    i INTEGER;
BEGIN
    current_date := CURRENT_DATE;
    
    FOR i IN 0..11 LOOP
        target_date := current_date + (i || ' months')::INTERVAL;
        PERFORM create_injection_partition(target_date);
        PERFORM create_production_partition(target_date);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- 4. 数据插入前自动创建分区的触发器
-- ============================================
CREATE OR REPLACE FUNCTION injection_partition_trigger_func()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM create_injection_partition(NEW.report_date);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION production_partition_trigger_func()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM create_production_partition(NEW.report_date);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS injection_partition_trigger ON water_injection_data;
CREATE TRIGGER injection_partition_trigger
BEFORE INSERT ON water_injection_data
FOR EACH ROW EXECUTE FUNCTION injection_partition_trigger_func();

DROP TRIGGER IF EXISTS production_partition_trigger ON production_data;
CREATE TRIGGER production_partition_trigger
BEFORE INSERT ON production_data
FOR EACH ROW EXECUTE FUNCTION production_partition_trigger_func();

-- ============================================
-- 5. 执行初始分区创建（过去2年+未来1年）
-- ============================================
DO $$
DECLARE
    start_date DATE;
    target_date DATE;
    i INTEGER;
BEGIN
    start_date := CURRENT_DATE - INTERVAL '2 years';
    
    FOR i IN 0..35 LOOP
        target_date := start_date + (i || ' months')::INTERVAL;
        PERFORM create_injection_partition(target_date);
        PERFORM create_production_partition(target_date);
    END LOOP;
END $$;

-- ============================================
-- 6. 空间索引优化
-- ============================================

-- 重建井位空间索引，使用更高的填充因子提高查询性能
DROP INDEX IF EXISTS idx_wells_geom;
CREATE INDEX idx_wells_geom ON wells USING GIST (geom)
WITH (fillfactor = 90);

-- 聚类表数据，提高空间查询性能
CLUSTER wells USING idx_wells_geom;

-- 分析表以更新统计信息
ANALYZE wells;
ANALYZE water_injection_data;
ANALYZE production_data;
ANALYZE injection_production_relation;

-- ============================================
-- 7. 创建常用查询的覆盖索引
-- ============================================

-- 注水井最新数据查询索引
CREATE INDEX IF NOT EXISTS idx_injection_well_date_include
ON water_injection_data (well_id, report_date DESC)
INCLUDE (water_volume, injection_pressure, water_absorption_index);

-- 采油井最新数据查询索引
CREATE INDEX IF NOT EXISTS idx_production_well_date_include
ON production_data (well_id, report_date DESC)
INCLUDE (liquid_volume, oil_volume, water_cut, dynamic_fluid_level);

-- 区块汇总查询索引
CREATE INDEX IF NOT EXISTS idx_summary_block_date
ON block_daily_summary (block_name, summary_date DESC)
INCLUDE (total_oil_production, total_water_injection, average_water_cut);

-- 告警查询索引
CREATE INDEX IF NOT EXISTS idx_alarms_unacknowledged
ON alarms (is_acknowledged, alarm_time DESC)
INCLUDE (alarm_level, alarm_type, well_id, alarm_message);
