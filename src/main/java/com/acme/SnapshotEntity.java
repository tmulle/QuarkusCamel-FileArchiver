package com.acme;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.*;
import org.eclipse.microprofile.metrics.Snapshot;
import org.hibernate.annotations.GenericGenerator;

import javax.inject.Named;
import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Entity
@ToString
public class SnapshotEntity extends PanacheEntity {

    @Column(unique = true)
    private String fileName;
    private Long fileSize;

    @Column(unique = true)
    private String sha256Hash;

    private LocalDateTime uploadDate;


    public static Optional<SnapshotEntity> findByHash(String sha256Hash) {
        return find("sha256Hash", sha256Hash).firstResultOptional();
    }

    public static void deleteByHash(String sha256Hash) {
        Optional<SnapshotEntity> byHash = findByHash(sha256Hash);
        if (byHash.isPresent()) {
            byHash.get().delete();
        }
    }


}
