#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import json
import time
import random
import os
import threading
import logging
import argparse
from datetime import datetime, timedelta
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.server import HTTPServer, BaseHTTPRequestHandler
from threading import Thread

import paho.mqtt.client as mqtt

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('/app/logs/simulator.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

class SimulatorConfig:
    def __init__(self):
        self.MQTT_BROKER = os.environ.get('MQTT_BROKER', 'localhost')
        self.MQTT_PORT = int(os.environ.get('MQTT_PORT', 1883))
        self.MQTT_USERNAME = os.environ.get('MQTT_USERNAME', 'admin')
        self.MQTT_PASSWORD = os.environ.get('MQTT_PASSWORD', 'public')
        self.MQTT_TOPIC = os.environ.get('MQTT_TOPIC', 'oilfield/data')
        self.INJECTION_WELL_COUNT = int(os.environ.get('INJECTION_WELL_COUNT', 300))
        self.PRODUCTION_WELL_COUNT = int(os.environ.get('PRODUCTION_WELL_COUNT', 500))
        self.WATER_CUT_RISE_TREND = float(os.environ.get('WATER_CUT_RISE_TREND', 0.02))
        self.REPORT_SCHEDULE = os.environ.get('REPORT_SCHEDULE', 'daily')
        self.REPORT_HOUR = int(os.environ.get('REPORT_HOUR', 8))
        self.REPORT_INTERVAL = int(os.environ.get('REPORT_INTERVAL', 86400))
        self.MAX_WORKERS = int(os.environ.get('MAX_WORKERS', 20))
        self.HTTP_PORT = int(os.environ.get('HTTP_PORT', 8000))

config = SimulatorConfig()

WELL_BASE_DATA = {
    "injection": {},
    "production": {}
}

stats = {
    "total_published": 0,
    "total_failed": 0,
    "last_report_time": None,
    "current_day": 0,
    "start_time": datetime.now()
}

class HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/health':
            uptime = (datetime.now() - stats['start_time']).total_seconds()
            health_data = {
                "status": "healthy",
                "uptime_seconds": int(uptime),
                "total_published": stats['total_published'],
                "total_failed": stats['total_failed'],
                "last_report_time": stats['last_report_time'].isoformat() if stats['last_report_time'] else None,
                "injection_wells": config.INJECTION_WELL_COUNT,
                "production_wells": config.PRODUCTION_WELL_COUNT,
                "water_cut_rise_trend": config.WATER_CUT_RISE_TREND
            }
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(health_data).encode())
        elif self.path == '/metrics':
            metrics = f"""# HELP dtu_published_messages_total Total published messages
# TYPE dtu_published_messages_total counter
dtu_published_messages_total {stats['total_published']}
# HELP dtu_failed_messages_total Total failed messages
# TYPE dtu_failed_messages_total counter
dtu_failed_messages_total {stats['total_failed']}
# HELP dtu_wells_total Number of simulated wells
# TYPE dtu_wells_total gauge
dtu_wells_total{{type="injection"}} {config.INJECTION_WELL_COUNT}
dtu_wells_total{{type="production"}} {config.PRODUCTION_WELL_COUNT}
"""
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(metrics.encode())
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass

def start_http_server():
    server = HTTPServer(('0.0.0.0', config.HTTP_PORT), HealthHandler)
    logger.info(f"HTTP server started on port {config.HTTP_PORT}")
    server.serve_forever()

def generate_well_base_data():
    logger.info(f"Generating well base data: {config.INJECTION_WELL_COUNT} injection, {config.PRODUCTION_WELL_COUNT} production...")
    
    center_lat, center_lon = 38.75, 116.25
    blocks = ['东区', '西区', '南区']
    
    for i in range(config.INJECTION_WELL_COUNT):
        well_id = f"INJ-{i+1:04d}"
        angle = random.uniform(0, 2 * 3.14159)
        radius = random.uniform(0.5, 8.0)
        lat = center_lat + radius * 0.008 * random.uniform(-1, 1)
        lon = center_lon + radius * 0.01 * random.uniform(-1, 1)
        
        block = blocks[i % 3]
        
        WELL_BASE_DATA["injection"][well_id] = {
            "wellId": well_id,
            "wellName": f"注{i+1}井",
            "wellType": "INJECTION",
            "blockName": block,
            "latitude": lat,
            "longitude": lon,
            "designPressure": random.uniform(25.0, 45.0),
            "baseWaterVolume": random.uniform(80.0, 200.0),
            "basePressure": random.uniform(18.0, 35.0),
            "baseAbsorptionIndex": random.uniform(2.0, 10.0),
            "pressureTrend": random.uniform(-0.01, 0.03)
        }
    
    for i in range(config.PRODUCTION_WELL_COUNT):
        well_id = f"PRO-{i+1:04d}"
        angle = random.uniform(0, 2 * 3.14159)
        radius = random.uniform(0.5, 8.0)
        lat = center_lat + radius * 0.008 * random.uniform(-1, 1)
        lon = center_lon + radius * 0.01 * random.uniform(-1, 1)
        
        block = blocks[i % 3]
        
        WELL_BASE_DATA["production"][well_id] = {
            "wellId": well_id,
            "wellName": f"采{i+1}井",
            "wellType": "PRODUCTION",
            "blockName": block,
            "latitude": lat,
            "longitude": lon,
            "baseFluidVolume": random.uniform(50.0, 150.0),
            "baseOilVolume": random.uniform(5.0, 30.0),
            "baseWaterCut": random.uniform(60.0, 90.0),
            "baseFluidLevel": random.uniform(800.0, 1800.0),
            "waterCutTrend": config.WATER_CUT_RISE_TREND,
            "oilDeclineRate": random.uniform(-0.015, -0.005)
        }
    
    logger.info(f"Generated {len(WELL_BASE_DATA['injection'])} injection wells and {len(WELL_BASE_DATA['production'])} production wells")

def generate_injection_data(well_id, base_data, report_time, day_offset=0):
    day_factor = 1.0 + 0.1 * random.uniform(-1, 1)
    pressure_variation = base_data["pressureTrend"] * day_offset
    
    water_volume = base_data["baseWaterVolume"] * day_factor * (1 + random.uniform(-0.08, 0.08))
    pressure = base_data["basePressure"] + pressure_variation + random.uniform(-1.5, 1.5)
    absorption_index = base_data["baseAbsorptionIndex"] * (1 + random.uniform(-0.1, 0.1))
    
    if random.random() < 0.02:
        pressure = base_data["designPressure"] * random.uniform(0.85, 0.98)
    
    if random.random() < 0.005:
        pressure = base_data["designPressure"] * random.uniform(1.0, 1.1)
        logger.warning(f"Abnormal pressure for {well_id}: {pressure:.2f} MPa")
    
    return {
        "wellId": well_id,
        "wellType": "INJECTION",
        "reportTime": report_time.isoformat(),
        "waterVolume": round(max(0, water_volume), 2),
        "injectionPressure": round(max(0, pressure), 2),
        "absorptionIndex": round(max(0, absorption_index), 2)
    }

def generate_production_data(well_id, base_data, report_time, day_offset=0):
    day_factor = 1.0 + 0.1 * random.uniform(-1, 1)
    water_cut_variation = base_data["waterCutTrend"] * day_offset
    
    fluid_volume = base_data["baseFluidVolume"] * day_factor * (1 + random.uniform(-0.08, 0.08))
    oil_volume = base_data["baseOilVolume"] * (1 + base_data["oilDeclineRate"] * day_offset)
    oil_volume = oil_volume * (1 + random.uniform(-0.08, 0.08))
    
    water_cut = base_data["baseWaterCut"] + water_cut_variation + random.uniform(-1.5, 1.5)
    water_cut = max(0, min(99.5, water_cut))
    
    oil_volume = min(oil_volume, fluid_volume * (1 - water_cut / 100) * 0.95)
    
    fluid_level = base_data["baseFluidLevel"] + random.uniform(-50.0, 50.0)
    
    if random.random() < 0.02:
        water_cut = min(99.5, water_cut + random.uniform(3, 8))
        logger.warning(f"High water cut for {well_id}: {water_cut:.2f}%")
    
    if random.random() < 0.005:
        water_cut = min(99.5, water_cut + random.uniform(8, 15))
        logger.warning(f"Very high water cut for {well_id}: {water_cut:.2f}%")
    
    return {
        "wellId": well_id,
        "wellType": "PRODUCTION",
        "reportTime": report_time.isoformat(),
        "fluidVolume": round(max(0, fluid_volume), 2),
        "oilVolume": round(max(0, oil_volume), 2),
        "waterCut": round(water_cut, 2),
        "fluidLevel": round(max(0, fluid_level), 2)
    }

class DTUSimulator:
    def __init__(self):
        self.client = mqtt.Client(
            client_id=f"dtu-simulator-{int(time.time())}",
            clean_session=False
        )
        self.client.username_pw_set(config.MQTT_USERNAME, config.MQTT_PASSWORD)
        self.client.on_connect = self.on_connect
        self.client.on_publish = self.on_publish
        self.client.on_disconnect = self.on_disconnect
        self.connected = False
        self.running = False
    
    def on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            self.connected = True
            logger.info("Connected to MQTT broker successfully")
        else:
            logger.error(f"Failed to connect to MQTT broker, return code: {rc}")
            self.connected = False
    
    def on_publish(self, client, userdata, mid):
        stats['total_published'] += 1
        if stats['total_published'] % 500 == 0:
            logger.info(f"Published {stats['total_published']} messages")
    
    def on_disconnect(self, client, userdata, rc):
        logger.warning(f"Disconnected from MQTT broker, return code: {rc}")
        self.connected = False
    
    def connect(self):
        try:
            self.client.connect(config.MQTT_BROKER, config.MQTT_PORT, keepalive=60)
            self.client.loop_start()
            
            timeout = time.time() + 30
            while not self.connected and time.time() < timeout:
                time.sleep(0.1)
            
            return self.connected
        except Exception as e:
            logger.error(f"Connection error: {e}")
            return False
    
    def disconnect(self):
        self.running = False
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()
        logger.info(f"Disconnected. Total published: {stats['total_published']}, failed: {stats['total_failed']}")
    
    def publish_data(self, data):
        if not self.connected:
            if not self.connect():
                stats['total_failed'] += 1
                return False
        
        try:
            payload = json.dumps(data, ensure_ascii=False)
            result = self.client.publish(
                config.MQTT_TOPIC,
                payload,
                qos=1,
                retain=False
            )
            
            if result.rc != mqtt.MQTT_ERR_SUCCESS:
                stats['total_failed'] += 1
                return False
            
            return True
        except Exception as e:
            logger.error(f"Publish error: {e}")
            stats['total_failed'] += 1
            return False

def report_daily_data(simulator, report_date, day_offset=0):
    logger.info(f"Reporting data for {report_date.date()} (day offset: {day_offset})...")
    
    report_time = report_date
    stats['last_report_time'] = report_time
    stats['current_day'] = day_offset
    
    success_count = 0
    fail_count = 0
    
    with ThreadPoolExecutor(max_workers=config.MAX_WORKERS) as executor:
        futures = []
        
        for well_id, base_data in WELL_BASE_DATA["injection"].items():
            data = generate_injection_data(well_id, base_data, report_time, day_offset)
            futures.append(executor.submit(simulator.publish_data, data))
        
        for well_id, base_data in WELL_BASE_DATA["production"].items():
            data = generate_production_data(well_id, base_data, report_time, day_offset)
            futures.append(executor.submit(simulator.publish_data, data))
        
        for future in as_completed(futures):
            if future.result():
                success_count += 1
            else:
                fail_count += 1
    
    logger.info(f"Completed reporting for {report_date.date()}: success={success_count}, failed={fail_count}")
    return success_count, fail_count

def run_backfill_mode(simulator, start_date, end_date, speed_factor=1.0):
    current_date = start_date
    day_offset = 0
    
    total_days = (end_date - current_date).days + 1
    logger.info(f"Starting backfill from {start_date.date()} to {end_date.date()} ({total_days} days)")
    
    while current_date <= end_date and simulator.running:
        report_date = current_date.replace(hour=config.REPORT_HOUR, minute=0, second=0, microsecond=0)
        report_daily_data(simulator, report_date, day_offset)
        
        current_date += timedelta(days=1)
        day_offset += 1
        
        if speed_factor > 0 and current_date <= end_date:
            sleep_time = max(0.1, 1.0 / speed_factor)
            time.sleep(sleep_time)
    
    logger.info("Backfill completed")

def run_daily_mode(simulator):
    logger.info("Starting daily report mode...")
    
    while simulator.running:
        now = datetime.now()
        
        if now.hour == config.REPORT_HOUR and now.minute == 0:
            report_date = now.replace(second=0, microsecond=0)
            day_offset = (now.date() - stats['start_time'].date()).days
            report_daily_data(simulator, report_date, day_offset)
            time.sleep(60)
        else:
            time.sleep(30)

def run_continuous_mode(simulator, interval_seconds=3600):
    logger.info(f"Starting continuous report mode (interval: {interval_seconds}s)...")
    
    while simulator.running:
        now = datetime.now()
        day_offset = (now.date() - stats['start_time'].date()).days
        report_daily_data(simulator, now, day_offset)
        time.sleep(interval_seconds)

def main():
    parser = argparse.ArgumentParser(description="Enhanced 4G DTU Data Simulator for Oilfield")
    parser.add_argument(
        "--mode",
        choices=["daily", "continuous", "backfill"],
        default=config.REPORT_SCHEDULE,
        help="Simulation mode"
    )
    parser.add_argument("--start-date", type=str, help="Start date (YYYY-MM-DD) for backfill")
    parser.add_argument("--end-date", type=str, help="End date (YYYY-MM-DD) for backfill")
    parser.add_argument("--speed", type=float, default=1.0, help="Simulation speed factor")
    parser.add_argument("--interval", type=int, default=3600, help="Interval in seconds for continuous mode")
    
    args = parser.parse_args()
    
    generate_well_base_data()
    
    http_thread = Thread(target=start_http_server, daemon=True)
    http_thread.start()
    
    simulator = DTUSimulator()
    simulator.running = True
    
    try:
        if not simulator.connect():
            logger.error("Failed to connect to MQTT broker, retrying...")
            for i in range(5):
                time.sleep(5)
                if simulator.connect():
                    break
                logger.error(f"Connection attempt {i+2} failed")
            if not simulator.connected:
                logger.error("Failed to connect after multiple attempts, exiting")
                return
        
        if args.mode == "backfill":
            if not args.start_date or not args.end_date:
                start_date = datetime.now() - timedelta(days=90)
                end_date = datetime.now()
            else:
                start_date = datetime.strptime(args.start_date, "%Y-%m-%d")
                end_date = datetime.strptime(args.end_date, "%Y-%m-%d")
            run_backfill_mode(simulator, start_date, end_date, args.speed)
        
        elif args.mode == "daily":
            run_daily_mode(simulator)
        
        elif args.mode == "continuous":
            run_continuous_mode(simulator, args.interval)
    
    except KeyboardInterrupt:
        logger.info("Simulation stopped by user")
    except Exception as e:
        logger.error(f"Simulation error: {e}", exc_info=True)
    finally:
        simulator.disconnect()

if __name__ == "__main__":
    main()
