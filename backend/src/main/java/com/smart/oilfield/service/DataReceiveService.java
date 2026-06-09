package com.smart.oilfield.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.oilfield.dto.InjectionDataDTO;
import com.smart.oilfield.dto.ProductionDataDTO;
import com.smart.oilfield.entity.WaterInjectionData;
import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.repository.WaterInjectionDataRepository;
import com.smart.oilfield.repository.ProductionDataRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class DataReceiveService {

    @Autowired
    private WaterInjectionDataRepository injectionDataRepository;

    @Autowired
    private ProductionDataRepository productionDataRepository;

    @Autowired
    private WellRepository wellRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 100;
    private static final int BATCH_FLUSH_INTERVAL_MS = 5000;
    private static final int QUEUE_CAPACITY = 10000;

    private final BlockingQueue<WaterInjectionData> injectionDataQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<ProductionData> productionDataQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicInteger injectionQueueSize = new AtomicInteger(0);
    private final AtomicInteger productionQueueSize = new AtomicInteger(0);

    private ExecutorService batchExecutor;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        batchExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "data-batch-writer");
            t.setDaemon(true);
            return t;
        });

        batchExecutor.submit(this::processInjectionQueue);
        batchExecutor.submit(this::processProductionQueue);

        log.info("Data batch writing service initialized, batchSize={}, flushInterval={}ms",
                BATCH_SIZE, BATCH_FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (batchExecutor != null) {
            batchExecutor.shutdownNow();
        }
        flushAllQueues();
        log.info("Data batch writing service shutdown, remaining injection={}, production={}",
                injectionQueueSize.get(), productionQueueSize.get());
    }

    private void processInjectionQueue() {
        List<WaterInjectionData> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlushTime = System.currentTimeMillis();

        while (running || !injectionDataQueue.isEmpty()) {
            try {
                WaterInjectionData data = injectionDataQueue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null) {
                    batch.add(data);
                    injectionQueueSize.decrementAndGet();
                }

                long now = System.currentTimeMillis();
                if (batch.size() >= BATCH_SIZE || (now - lastFlushTime >= BATCH_FLUSH_INTERVAL_MS && !batch.isEmpty())) {
                    saveInjectionBatch(batch);
                    batch.clear();
                    lastFlushTime = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing injection queue", e);
                if (!batch.isEmpty()) {
                    saveInjectionBatch(batch);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            saveInjectionBatch(batch);
        }
    }

    private void processProductionQueue() {
        List<ProductionData> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlushTime = System.currentTimeMillis();

        while (running || !productionDataQueue.isEmpty()) {
            try {
                ProductionData data = productionDataQueue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null) {
                    batch.add(data);
                    productionQueueSize.decrementAndGet();
                }

                long now = System.currentTimeMillis();
                if (batch.size() >= BATCH_SIZE || (now - lastFlushTime >= BATCH_FLUSH_INTERVAL_MS && !batch.isEmpty())) {
                    saveProductionBatch(batch);
                    batch.clear();
                    lastFlushTime = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing production queue", e);
                if (!batch.isEmpty()) {
                    saveProductionBatch(batch);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            saveProductionBatch(batch);
        }
    }

    private void saveInjectionBatch(List<WaterInjectionData> batch) {
        if (batch.isEmpty()) return;
        try {
            long start = System.currentTimeMillis();
            injectionDataRepository.saveAll(batch);
            log.info("Batch saved {} injection records in {}ms, queue remaining: {}",
                    batch.size(), System.currentTimeMillis() - start, injectionQueueSize.get());
        } catch (Exception e) {
            log.error("Failed to save injection batch of size {}", batch.size(), e);
        }
    }

    private void saveProductionBatch(List<ProductionData> batch) {
        if (batch.isEmpty()) return;
        try {
            long start = System.currentTimeMillis();
            productionDataRepository.saveAll(batch);
            log.info("Batch saved {} production records in {}ms, queue remaining: {}",
                    batch.size(), System.currentTimeMillis() - start, productionQueueSize.get());
        } catch (Exception e) {
            log.error("Failed to save production batch of size {}", batch.size(), e);
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void flushAllQueues() {
        if (injectionQueueSize.get() > 0 || productionQueueSize.get() > 0) {
            log.debug("Scheduled flush triggered, injection queue: {}, production queue: {}",
                    injectionQueueSize.get(), productionQueueSize.get());
        }
    }

    public void receiveInjectionData(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String wellId = node.get("wellId").asText();

            if (!wellRepository.existsById(wellId)) {
                log.warn("Received data for unknown injection well: {}", wellId);
                return;
            }

            WaterInjectionData data = new WaterInjectionData();
            data.setWellId(wellId);
            data.setReportDate(node.has("reportDate") ?
                    LocalDate.parse(node.get("reportDate").asText()) : LocalDate.now());
            data.setWaterVolume(node.get("waterVolume").asDouble());
            data.setInjectionPressure(node.get("injectionPressure").asDouble());
            data.setWaterAbsorptionIndex(node.get("waterAbsorptionIndex").asDouble());

            boolean offered = injectionDataQueue.offer(data, 1, TimeUnit.SECONDS);
            if (offered) {
                injectionQueueSize.incrementAndGet();
            } else {
                log.warn("Injection data queue full, dropping data for well: {}", wellId);
            }

        } catch (Exception e) {
            log.error("Failed to parse injection data: {}", payload, e);
        }
    }

    public void receiveProductionData(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String wellId = node.get("wellId").asText();

            if (!wellRepository.existsById(wellId)) {
                log.warn("Received data for unknown production well: {}", wellId);
                return;
            }

            ProductionData data = new ProductionData();
            data.setWellId(wellId);
            data.setReportDate(node.has("reportDate") ?
                    LocalDate.parse(node.get("reportDate").asText()) : LocalDate.now());
            data.setLiquidVolume(node.get("liquidVolume").asDouble());
            data.setOilVolume(node.get("oilVolume").asDouble());
            data.setWaterCut(node.get("waterCut").asDouble());
            data.setDynamicFluidLevel(node.get("dynamicFluidLevel").asDouble());

            boolean offered = productionDataQueue.offer(data, 1, TimeUnit.SECONDS);
            if (offered) {
                productionQueueSize.incrementAndGet();
            } else {
                log.warn("Production data queue full, dropping data for well: {}", wellId);
            }

        } catch (Exception e) {
            log.error("Failed to parse production data: {}", payload, e);
        }
    }

    public WaterInjectionData saveInjectionData(InjectionDataDTO dto) {
        if (!wellRepository.existsById(dto.getWellId())) {
            throw new RuntimeException("Well not found: " + dto.getWellId());
        }
        WaterInjectionData data = new WaterInjectionData();
        data.setWellId(dto.getWellId());
        data.setReportDate(dto.getReportDate() != null ? dto.getReportDate() : LocalDate.now());
        data.setWaterVolume(dto.getWaterVolume());
        data.setInjectionPressure(dto.getInjectionPressure());
        data.setWaterAbsorptionIndex(dto.getWaterAbsorptionIndex());

        try {
            boolean offered = injectionDataQueue.offer(data, 1, TimeUnit.SECONDS);
            if (offered) {
                injectionQueueSize.incrementAndGet();
                return data;
            } else {
                log.warn("Queue full, falling back to direct save for well: {}", dto.getWellId());
                return injectionDataRepository.save(data);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return injectionDataRepository.save(data);
        }
    }

    public ProductionData saveProductionData(ProductionDataDTO dto) {
        if (!wellRepository.existsById(dto.getWellId())) {
            throw new RuntimeException("Well not found: " + dto.getWellId());
        }
        ProductionData data = new ProductionData();
        data.setWellId(dto.getWellId());
        data.setReportDate(dto.getReportDate() != null ? dto.getReportDate() : LocalDate.now());
        data.setLiquidVolume(dto.getLiquidVolume());
        data.setOilVolume(dto.getOilVolume());
        data.setWaterCut(dto.getWaterCut());
        data.setDynamicFluidLevel(dto.getDynamicFluidLevel());

        try {
            boolean offered = productionDataQueue.offer(data, 1, TimeUnit.SECONDS);
            if (offered) {
                productionQueueSize.incrementAndGet();
                return data;
            } else {
                log.warn("Queue full, falling back to direct save for well: {}", dto.getWellId());
                return productionDataRepository.save(data);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return productionDataRepository.save(data);
        }
    }

    public int getInjectionQueueSize() {
        return injectionQueueSize.get();
    }

    public int getProductionQueueSize() {
        return productionQueueSize.get();
    }
}
