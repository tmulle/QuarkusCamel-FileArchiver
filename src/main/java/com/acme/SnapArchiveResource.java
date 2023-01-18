package com.acme;


import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.*;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.StreamCache;
import org.apache.camel.attachment.AttachmentMessage;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.converter.stream.FileInputStreamCache;
import org.apache.camel.converter.stream.InputStreamCache;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.DataHandler;
import javax.enterprise.context.ApplicationScoped;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.transaction.Transactional;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class SnapArchiveResource extends RouteBuilder {

    private final static Logger LOG = LoggerFactory.getLogger(SnapArchiveResource.class);


    @ConfigProperty(name = "upload.file.location")
    String uploadLocation;


    @Override
    public void configure() throws Exception {

        getContext().getStreamCachingStrategy().setSpoolEnabled(true);

        onException(InvalidRequestException.class).handled(true).process(exchange -> {
            Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, MediaType.APPLICATION_JSON);
            JsonObjectBuilder builder = Json.createObjectBuilder();
            builder.add("error", cause.getMessage());
            exchange.getMessage().setBody(builder.build().toString());

        });

        onException(Exception.class).handled(true).process(exchange -> {
            Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
            exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, MediaType.APPLICATION_JSON);

            JsonObjectBuilder builder = Json.createObjectBuilder();
            builder.add("error", cause.getMessage());
            exchange.getMessage().setBody(builder.build().toString());

        });

        // Setup the Jackson Parser to support JDK8 date classes
        JacksonDataFormat df = new JacksonDataFormat();
        df.setModuleClassNames("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
        df.setAutoDiscoverObjectMapper(true);

        restConfiguration()
                .contextPath("archive")
//                .inlineRoutes(true)
                .enableCORS(true);
        // This is supposed to work for the configuring Jackson but doesn't
//                .dataFormatProperty("prettyPrint", "true")
//                .dataFormatProperty("autoDiscoverObjectMapper", "true")
//                .dataFormatProperty("moduleClassNames", "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");


        rest().post()
                .routeId("file-upload")
                .consumes("multipart/form-data")
                .produces("application/json")
                .to("direct:upload")
                .get("/{hash}")
                .produces(MediaType.APPLICATION_OCTET_STREAM)
                .routeId("downloadFile")
                .to("direct:downloadFile")
                .get().routeId("get-all").to("direct:displayAll")
                .get("/info/id/{fileId}").routeId("get-single-info-id").to("direct:displaySingleById")
                .get("/info/hash/{hashId}").routeId("get-single-info-hash").to("direct:displaySingleByHash")
                .delete("{fileId}").to("direct:deleteSingle");


        from("direct:downloadFile")
                .routeId("file-downloader")
                .process(this::getFileNameFromDB)
                .log("Sending to pollEnrich ${header.CamelFileName}")
                .process(this::loadFileFromDisk)
//                .enrich().simple("file:" + uploadLocation + "?fileName=${header.CamelFileName}")
//                .cacheSize(-1)

//                .pollEnrich("file:" + uploadLocation + "?fileName=${header.CamelFileName}&noop=true", 0)
                .log("After enrich ${header.CamelFileName}")
                .setHeader("Content-Disposition", simple("attachment;filename=${header.CamelFileName}"));

        /**
         * Handle file upload
         */
        from("direct:upload")
                .routeId("direct-upload")
                .process(new AttachmentProcessor())
                .toF("file://%s", uploadLocation)
                .to("log:afterUpload")
                .process(this::computeFileSizeAndHash)
                .to("log:afterComputeHash")
                .process(this::saveToDB)
                .to("log:afterSaveToDB")
                .to("direct:printResults")
                .to("direct:findDb")
                .marshal(df)
                .to("log:endStep");


        from("direct:printResults")
                .routeId("final-route")
                .process(this::handleIt);

        from("direct:findDb")
                .routeId("find-in-db")
                .process(this::findInDB);

        from("direct:displayAll").process(this::displayAll).marshal(df);
        from("direct:displaySingleById").process(this::displaySingleById).marshal(df);
        from("direct:displaySingleByHash").process(this::displaySingleByHash).marshal(df);
        from("direct:deleteSingle").process(this::deleteSingleById).marshal(df);

    }

    /**
     * Load the file from disk
     *
     * @param exchange
     * @throws Exception
     */
    private void loadFileFromDisk(Exchange exchange) throws Exception {
        String fileName = exchange.getMessage().getHeader(Exchange.FILE_NAME, String.class);
        Path path = Paths.get(uploadLocation).resolve(fileName);

        LOG.info("Loading file {} from disk", path.toAbsolutePath());
        if (!Files.exists(path)) {
            throw new InvalidRequestException("File doesn't exist");
        }

        // Set the input stream into the body for further processing
        exchange.getMessage().setBody(new FileInputStream(path.toFile()));
    }

    @Transactional
    public void getFileNameFromDB(Exchange exchange) {
        String hashID = exchange.getMessage().getHeader("hash", String.class);
        Optional<SnapshotEntity> hash = SnapshotEntity.findByHash(hashID);
        if (hash.isPresent()) {
            LOG.info("Adding filename from DB to headers: {}", hash.get().getFileName());
            exchange.getMessage().setHeader(Exchange.FILE_NAME, hash.get().getFileName());
        } else {
            throw new InvalidRequestException("Hash " + hashID + " not found");
        }

    }

    @Transactional
    public void deleteSingleById(Exchange exchange) throws Exception {
        Long fileID = exchange.getMessage().getHeader("fileId", Long.class);
        Optional<SnapshotEntity> optional = SnapshotEntity.findByIdOptional(fileID);
        if (optional.isPresent()) {

            SnapshotEntity entity = optional.get();
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setBody(null);

            // Delete the file from disk
            Path path = Path.of(uploadLocation).resolve(entity.getFileName());
            if (deleteFile(path.toFile())) {
                // Delete from DB
                entity.delete();
            } else {
                throw new RuntimeException("Could not delete file " + entity.getFileName());
            }


        } else {
            throw new InvalidRequestException("ID " + fileID + " does not exist");
        }

    }

    private boolean deleteFile(File file) {
        return file.delete();
    }

    @Transactional
    public void displaySingleById(Exchange exchange) throws Exception {
        Long fileID = exchange.getMessage().getHeader("fileId", Long.class);
        Optional<PanacheEntityBase> optional = SnapshotEntity.findByIdOptional(fileID);
        if (optional.isPresent()) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setBody(optional.get());
        } else {
            throw new InvalidRequestException("ID " + fileID + " does not exist");
        }
    }

    @Transactional
    public void displaySingleByHash(Exchange exchange) throws Exception {
        String hashID = exchange.getMessage().getHeader("hashId", String.class);
        Optional<SnapshotEntity> optional = SnapshotEntity.findByHash(hashID);
        if (optional.isPresent()) {
            exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
            exchange.getMessage().setBody(optional.get());
        } else {
            throw new InvalidRequestException("Hash " + hashID + " does not exist");
        }
    }

    @Transactional
    public void displayAll(Exchange exchange) throws Exception {
        exchange.getMessage().setBody(SnapshotEntity.listAll());
    }

    @Transactional
    public void saveToDB(Exchange exchange) {

        String fileName = exchange.getMessage().getHeader(Exchange.FILE_NAME, String.class);
        String sha256Hash = exchange.getMessage().getHeader("SHA-256", String.class);
        Long fileSize = exchange.getMessage().getHeader("FileSize", Long.class);


        exchange.getMessage().setHeader("UploadDate", LocalDateTime.now());

        if (SnapshotEntity.count("sha256Hash", sha256Hash) > 0) {
            throw new InvalidRequestException("A record with this hash already exists");
        }

        SnapshotEntity entity = SnapshotEntity.builder()
                .fileSize(fileSize)
                .fileName(fileName)
                .sha256Hash(sha256Hash)
                .uploadDate(LocalDateTime.now())
                .build();
        entity.persist();
    }

    /**
     * Compute the hash of the uploaded file
     *
     * @param exchange
     */
    private void computeFileSizeAndHash(Exchange exchange) throws Exception {

        // Reuse the incoming cached data so we don't have to reload it from disk
        StreamCache body = exchange.getMessage().getBody(StreamCache.class);
        long size = -1;
        String hash = null;

        if (body instanceof InputStreamCache inputStr) {
            size = inputStr.length();
            hash = DigestUtils.sha256Hex(inputStr);
        } else if (body instanceof FileInputStreamCache fileInputStreamCache) {
            size = fileInputStreamCache.length();
            hash = DigestUtils.sha256Hex(fileInputStreamCache);
        } else {
            throw new IllegalArgumentException("Unhandled Stream type " + body.getClass());
        }

        // Set the headers
        exchange.getMessage().setHeader("FileSize", size);
        exchange.getMessage().setHeader("SHA-256",hash );
    }

    @Transactional
    public void findInDB(Exchange exchange) {
        UploadResult body = exchange.getMessage().getBody(UploadResult.class);
        String header = body.sha256Hash;//exchange.getMessage().getHeader("SHA-256", String.class);
        Optional<SnapshotEntity> byHash = SnapshotEntity.findByHash(header);
        if (byHash.isPresent()) {
            exchange.getMessage().setBody(byHash.get());
        } else {
            exchange.getMessage().setBody(null);
        }
    }


    @Transactional
    public void handleIt(Exchange exchange) {
        String fileNameHeader = exchange.getMessage().getHeader(Exchange.FILE_NAME, String.class);
        String sha256Hash = exchange.getMessage().getHeader("SHA-256", String.class);
        Long fileSize = exchange.getMessage().getHeader("FileSize", Long.class);
        LocalDateTime uploadDate = exchange.getMessage().getHeader("UploadDate", LocalDateTime.class);

        UploadResult result = UploadResult.builder()
                .fileName(fileNameHeader)
                .sha256Hash(sha256Hash)
                .fileSize(fileSize)
                .uploadDate(uploadDate)
                .build();

        System.out.println(result);
        exchange.getMessage().setBody(result);
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    static class UploadResult {
        String fileName;
        Long fileSize;
        String sha256Hash;
        LocalDateTime uploadDate;
    }

    private class AttachmentProcessor implements Processor {

        private final static Logger LOG = LoggerFactory.getLogger(AttachmentProcessor.class);

        @Override
        public void process(Exchange e) throws Exception {
            Map<String, DataHandler> attachments = e.getMessage(AttachmentMessage.class).getAttachments();
            LOG.info("Attachments: {}", attachments);

            if (attachments.isEmpty()) {
                LOG.info("No attachments..returning");
                return;
            }

            Iterator<Map.Entry<String, DataHandler>> iterator = attachments.entrySet().iterator();
            Map.Entry<String, DataHandler> entry = iterator.next();
            e.getIn().setHeader("CamelFileName", entry.getKey());

            InputStream in = entry.getValue().getInputStream();
            e.getIn().setBody(in);


            LOG.info("AttachmentProcessor complete");
        }
    }

}
