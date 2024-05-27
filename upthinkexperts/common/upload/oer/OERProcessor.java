// 
// Decompiled by Procyon v0.6.0
// 

package com.upthinkexperts.common.upload.oer;

import org.jdbi.v3.core.Handle;
import com.upthinkexperts.common.parsing.DocxRelationshipProcessor;
import com.upthinkexperts.common.parsing.spec.ParsingSpec;
import java.util.Optional;
import com.yojito.minima.gson.GsonArray;
import com.yojito.minima.gson.GsonObject;
import com.upthinkexperts.common.domain.DocResources;
import com.upthinkexperts.common.db.DocDB;
import com.upthinkexperts.common.util.QBankEquationProcessor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import com.yojito.minima.db.Database;
import com.upthinkexperts.common.db.UploadJobDB;
import com.amazonaws.services.s3.model.S3Object;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.zip.ZipFile;
import java.io.InputStream;
import com.amazonaws.util.IOUtils;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import com.amazonaws.services.s3.AmazonS3;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.zip.ZipEntry;
import com.upthinkexperts.common.domain.JobFile;
import java.util.Map;
import java.io.File;
import com.upthinkexperts.common.util.CmsCommonConfig;
import com.upthinkexperts.common.domain.UploadJob;
import com.yojito.minima.logging.MinimaLogger;

public class OERProcessor
{
    private static final MinimaLogger LOGGER;
    private final UploadJob uploadJob;
    private final CmsCommonConfig cmsConfig;
    private final File localFile;
    private final File localXtractDir;
    private final Map<String, String> docFiles;
    private final Map<String, JobFile> jobFileMap;
    private final Map<String, ZipEntry> jobFileZipMap;
    private final List<CompletableFuture> futureList;
    private AmazonS3 s3Client;
    private String SOFFICE_PATH;
    private String MOGRIFY_PATH;
    private final OERBatch oerBatch;
    
    public OERProcessor(final UploadJob uploadJob, final CmsCommonConfig context) {
        this.docFiles = new HashMap<String, String>();
        this.jobFileMap = new HashMap<String, JobFile>();
        this.jobFileZipMap = new HashMap<String, ZipEntry>();
        this.futureList = new ArrayList<CompletableFuture>();
        this.s3Client = AmazonS3ClientBuilder.defaultClient();
        this.SOFFICE_PATH = "";
        this.MOGRIFY_PATH = "";
        this.oerBatch = new OERBatch();
        this.uploadJob = uploadJob;
        this.cmsConfig = context;
        try {
            this.localFile = File.createTempFile(uploadJob.getFileName(), ".froms3");
            this.localXtractDir = new File("/tmp/" + System.nanoTime());
            boolean pathCreated = this.localXtractDir.mkdirs();
            this.checkPath(this.localXtractDir, pathCreated);
            final File oleBinDir = new File(String.format("%s/%s", this.localXtractDir, "olebin"));
            final File mmlDir = new File(String.format("%s/%s", this.localXtractDir, "mml"));
            final File imagesDir = new File(String.format("%s/%s", this.localXtractDir, "images"));
            final File imagesDir2 = new File(String.format("%s/%s", this.localXtractDir, "images2"));
            pathCreated = oleBinDir.mkdirs();
            this.checkPath(oleBinDir, pathCreated);
            pathCreated = mmlDir.mkdirs();
            this.checkPath(mmlDir, pathCreated);
            pathCreated = imagesDir.mkdirs();
            this.checkPath(imagesDir, pathCreated);
            pathCreated = imagesDir2.mkdirs();
            this.checkPath(imagesDir2, pathCreated);
            this.SOFFICE_PATH = context.getsOfficePath();
            this.MOGRIFY_PATH = context.getMogrifyPath();
        }
        catch (final IOException e) {
            e.printStackTrace();
            OERProcessor.LOGGER.error((Throwable)e, "QBankProcessor Creation Failed", new Object[0]);
            throw new RuntimeException();
        }
    }
    
    private void checkPath(final File localXtractDir, final boolean pathCreated) {
        if (!pathCreated && !localXtractDir.exists()) {
            OERProcessor.LOGGER.error("File path " + localXtractDir.getAbsolutePath() + " can not be created", new Object[0]);
            throw new RuntimeException("File path " + localXtractDir.getAbsolutePath() + " can not be created");
        }
    }
    
    public void downloadAndExtract() throws IOException {
        final String s3FileKey = String.format("%s/%s", this.uploadJob.getUuid(), this.uploadJob.getFileName());
        OERProcessor.LOGGER.info("Downloading %s", new Object[] { this.uploadJob.getFileName() });
        final S3Object s3Object = this.s3Client.getObject(this.cmsConfig.getBucket(), s3FileKey);
        final BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(this.localFile));
        IOUtils.copy((InputStream)s3Object.getObjectContent(), (OutputStream)bufferedOutputStream);
        bufferedOutputStream.flush();
        bufferedOutputStream.close();
        OERProcessor.LOGGER.info("Downloaded %s to %s", new Object[] { this.uploadJob.getFileName(), this.localFile });
        final ZipFile zipFile = new ZipFile(this.localFile);
        final Set<String> fileNames = zipFile.stream().map(zipEntry -> zipEntry.getName()).collect((Collector<? super Object, ?, Set<String>>)Collectors.toSet());
        this.buildDocFileMap(fileNames, zipFile);
        OERProcessor.LOGGER.info("jobFileMap %s", new Object[] { this.jobFileMap.entrySet().stream().map(e -> (String)e.getKey() + "-> " + e.getValue().toStringPretty()).collect((Collector<? super Object, ?, String>)Collectors.joining("\n")) });
    }
    
    private void buildDocFileMap(final Set<String> fileNames, final ZipFile zipFile) {
        fileNames.forEach(docFile -> {
            if (docFile.endsWith(".docx")) {
                final String docId = docFile.substring(0, docFile.length() - 5);
                final ZipEntry jobFileEntry = (ZipEntry)zipFile.stream().filter(z -> z.getName().equals(docFile)).findFirst().get();
                final JobFile jobFile = new JobFile(docId, this.uploadJob.getUuid(), docFile, false, this.uploadJob.getTenantKey());
                this.jobFileMap.put(docFile, jobFile);
                this.jobFileZipMap.put(docFile, jobFileEntry);
                this.docFiles.put(docId, docFile);
            }
        });
    }
    
    public void createFileEntries() {
        Database.withinTx(this.cmsConfig.getDb(), handle -> UploadJobDB.insertUploadJobFiles(handle, this.jobFileMap.values(), this.uploadJob.getTenantKey()));
    }
    
    public void markComplete() {
        Database.doWithinTx(this.cmsConfig.getDb(), handle -> UploadJobDB.updateUploadJobStatus(handle, this.uploadJob.getUuid(), UploadJob.UploadJobStatus.INDEXED_PROCESSED));
    }
    
    public void processDocs() throws IOException {
        final ZipFile zipFile = new ZipFile(this.localFile);
        this.jobFileZipMap.entrySet().forEach(docFileEntryKV -> {
            final String name = docFileEntryKV.getKey();
            final ZipEntry docFileZipEntry = docFileEntryKV.getValue();
            final OERFileProcessor processor = new OERFileProcessor(this.jobFileMap.get(name), this.uploadJob, this.cmsConfig, this.oerBatch, docFileZipEntry);
            processor.setZipFile(zipFile);
            processor.setTempFilePath(this.localXtractDir.getAbsolutePath());
            final Executor workerPool = this.cmsConfig.getWorkerPool();
            final CompletableFuture future = CompletableFuture.runAsync(processor, workerPool);
            this.futureList.add(future);
            return;
        });
        Database.withinTx(this.cmsConfig.getDb(), handle -> UploadJobDB.updateUploadJobStatus(handle, this.uploadJob.getUuid(), UploadJob.UploadJobStatus.INDEXING));
    }
    
    public void awaitDocProcessing() {
        try {
            final CompletableFuture[] array = this.futureList.toArray(new CompletableFuture[0]);
            CompletableFuture.allOf((CompletableFuture<?>[])array).get(15L, TimeUnit.MINUTES);
            OERProcessor.LOGGER.info("Doc Processing for retrieving questions is completed", new Object[0]);
        }
        catch (final TimeoutException e) {
            throw new RuntimeException(e);
        }
        catch (final ExecutionException e2) {
            throw new RuntimeException(e2);
        }
        catch (final InterruptedException e3) {
            throw new RuntimeException(e3);
        }
    }
    
    public Map<String, List<String>> processEquations() {
        final Map<String, String> equationsJsonFiles = new HashMap<String, String>();
        final Map<String, List<String>> failedEquations = new HashMap<String, List<String>>();
        try {
            final QBankEquationProcessor equationProcessor = new QBankEquationProcessor(this.cmsConfig.getCalabashPath(), this.cmsConfig.getJdkPath());
            equationProcessor.setMinMemory(this.cmsConfig.getCalabashMinMemory());
            equationProcessor.setMaxMemory(this.cmsConfig.getCalabashMinMemory());
            final String OLE_BIN_PREFIX = String.format("%s/%s", this.localXtractDir, "olebin");
            final String MML_PREFIX = String.format("%s/%s", this.localXtractDir, "mml");
            equationProcessor.transformEquations(OLE_BIN_PREFIX, MML_PREFIX);
            this.docFiles.keySet().forEach(docName -> {
                final boolean hasParsingFailed = this.oerBatch.hasDocParsingFailedForDoc(docName);
                if (!hasParsingFailed) {
                    final String docId = this.oerBatch.getDocId(docName);
                    try {
                        final GsonObject equationJson = equationProcessor.createEquationJson2(MML_PREFIX, docId, docName);
                        final List<String> failedEquations_q = new ArrayList<String>();
                        final GsonArray array = equationJson.getArray("qs");
                        for (int i = 0; i < array.size(); ++i) {
                            final GsonObject eq = array.getJson(i);
                            final boolean useImage = eq.optBoolean("useImage", false);
                            if (useImage) {
                                failedEquations_q.add(eq.getString("name"));
                            }
                        }
                        failedEquations.put(docId, failedEquations_q);
                        final String json = equationJson.toStringPretty();
                        final String key = String.format("%s/%s-equations.json", this.uploadJob.getUuid(), docId);
                        OERProcessor.LOGGER.debug("Uploading Equations JSON file for docId = %s", new Object[] { docId });
                        this.s3Client.putObject(this.cmsConfig.getBucket(), key, json);
                        equationsJsonFiles.put(docId, key);
                        OERProcessor.LOGGER.debug("Uploaded Equations JSON file for docId = %s", new Object[] { docId });
                    }
                    catch (final Exception e2) {
                        OERProcessor.LOGGER.error((Throwable)e2, "Error while processing equations json for %s", new Object[] { docId });
                    }
                }
                else {
                    OERProcessor.LOGGER.warn("Skipping processing of Equations for doc = %s as parsing has failed", new Object[] { docName });
                }
                return;
            });
            Database.doWithinTx(this.cmsConfig.getDb(), handle -> equationsJsonFiles.forEach((docId, s3Key) -> {
                if (!this.oerBatch.hasDocParsingFailedForDocId(docId)) {
                    final Optional<DocResources> dbDocResources = DocDB.loadResources(handle, docId);
                    if (dbDocResources.isPresent()) {
                        final DocResources docResources = dbDocResources.get().withEquationS3Key(s3Key);
                        DocDB.updateResources(handle, docId, docResources);
                    }
                }
            }));
            OERProcessor.LOGGER.debug("Failed Equations => %s", new Object[] { failedEquations });
            return failedEquations;
        }
        catch (final IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    
    public void createImagesForFailedEquations(final Map<String, List<String>> failedEquations) {
    }
    
    public void processImages() {
        try {
            final String IMAGE_PREFIX = String.format("%s/%s", this.localXtractDir, "images");
            final Map<String, String> imageJsonFiles = new HashMap<String, String>();
            this.docFiles.keySet().forEach(docName -> {
                if (!this.oerBatch.hasDocParsingFailedForDoc(docName)) {
                    try {
                        final String docId = this.oerBatch.getDocId(docName);
                        final String json = DocxRelationshipProcessor.createImageMap(ParsingSpec.DocFormat.OER, this.uploadJob.getUuid(), IMAGE_PREFIX, docName).toJson();
                        final String key = String.format("%s/%s-images.json", this.uploadJob.getUuid(), docId);
                        OERProcessor.LOGGER.debug("Uploading Image JSON file for docId = [%s]", new Object[] { docId });
                        this.s3Client.putObject(this.cmsConfig.getBucket(), key, json);
                        OERProcessor.LOGGER.debug("Uploaded Image JSON file for docId = [%s]", new Object[] { docId });
                        imageJsonFiles.put(docId, key);
                    }
                    catch (final Exception e2) {
                        OERProcessor.LOGGER.error((Throwable)e2, "Error while processImages json for %s", new Object[] { docName });
                    }
                }
                else {
                    OERProcessor.LOGGER.warn("Skipping processing of Image for doc = %s as parsing has failed", new Object[] { docName });
                }
                return;
            });
            Database.doWithinTx(this.cmsConfig.getDb(), handle -> imageJsonFiles.forEach((docId, s3Key) -> {
                final Optional<DocResources> dbDocResources = DocDB.loadResources(handle, docId);
                if (dbDocResources.isPresent()) {
                    final DocResources docResources = dbDocResources.get().withImagesS3Key(s3Key);
                    DocDB.updateResources(handle, docId, docResources);
                }
            }));
        }
        catch (final Exception e) {
            OERProcessor.LOGGER.error((Throwable)e, "Error while processing images", new Object[0]);
            throw e;
        }
    }
    
    public void cleanup() {
        this.s3Client.shutdown();
    }
    
    static {
        LOGGER = MinimaLogger.getLog((Class)OERProcessor.class);
    }
}