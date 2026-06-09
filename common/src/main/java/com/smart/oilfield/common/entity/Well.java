package com.smart.oilfield.common.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.locationtech.jts.geom.Point;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "wells")
public class Well {
    @Id
    @Column(name = "well_id", length = 32)
    private String wellId;

    @Column(name = "well_name", length = 100, nullable = false)
    private String wellName;

    @Column(name = "well_type", length = 20, nullable = false)
    private String wellType;

    @Column(name = "block_name", length = 100, nullable = false)
    private String blockName;

    @Column(name = "design_pressure", precision = 10, scale = 2)
    private Double designPressure;

    @Column(name = "geom", columnDefinition = "Geometry(Point, 4326)", nullable = false)
    private Point geom;

    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public Double getLongitude() {
        return geom != null ? geom.getX() : null;
    }

    public Double getLatitude() {
        return geom != null ? geom.getY() : null;
    }
}
