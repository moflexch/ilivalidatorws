package ch.so.agi.ilivalidator.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import ch.interlis.iox.IoxException;
import ch.so.agi.ilivalidator.Utils;
import ch.so.agi.ilivalidator.model.JobResponse;
import ch.so.agi.ilivalidator.model.ValidationResult;
import ch.so.agi.ilivalidator.model.ValidationType;
import ch.so.agi.ilivalidator.service.CsvValidatorService;
import ch.so.agi.ilivalidator.service.FilesystemStorageService;
import ch.so.agi.ilivalidator.service.IlivalidatorService;
import ch.so.agi.ilivalidator.service.StorageService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.FilenameUtils;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.states.JobState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ConditionalOnProperty(
        value="app.restApiEnabled", 
        havingValue = "true", 
        matchIfMissing = false)
@RestController
public class ApiController {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private static final String LOG_ENDPOINT = "logs";
    private static final String JOB_NOT_FOUND = "Job not found";
    private static final String JOB_DELETED = "Job successfully deleted";
    
//    @Autowired
//    private FilesystemStorageService fileStorageService;

    //@Autowired
    private StorageService storageService;

    @Autowired
    private JobScheduler jobScheduler;
        
    @Autowired
    StorageProvider storageProvider;
    
    @Autowired
    private IlivalidatorService ilivalidatorService;
    
    @Autowired
    private CsvValidatorService csvvalidatorService;

    public ApiController(StorageService storageService) {
        this.storageService = storageService;
        log.debug("Storage service implementation: {}", storageService.getClass().getName());
    }
    
    
    @PostMapping(value="/api/jobs", consumes = {"multipart/form-data"})
    // @RequestPart anstelle von @RequestParam und @RequestBody damit swagger korrekt funktioniert.
    // Sonst kann man zwar Dateien auswählen aber Swagger reklamiert im Browser, dass es Strings sein müssen.
    public ResponseEntity<?> uploadFiles(@RequestPart(name="files", required=true) MultipartFile[] files) {
        // Wir erstellen vorab eine JobId, damit man Logeinträge dieser JobId zuordnen kann.
        UUID jobIdUuid = UUID.randomUUID();
        String jobId = jobIdUuid.toString();
        
        log.debug("<{}> Number of uploaded files: {}", jobId, files.length);
        
        Path[] uploadedFiles = storageService.store(files);

        // Mit einem einfachen Ansatz wird versucht herauszufinden, welcher Validierungstyp verwendet werden muss.
        // D.h. welches Format vorliegt und geprüft werden soll.
        // Nach einem ersten Auffinden eines bekannten Filetypes wird aufgehört.
        ValidationType validationType = null;
        for (Path path : uploadedFiles) {
            String fileName = path.toFile().toString().toLowerCase();
            if (fileName.endsWith("xtf") || fileName.endsWith("xml") || fileName.endsWith("itf")) {
                validationType = ValidationType.INTERLIS;
                break;
            } else if (fileName.endsWith("csv")) {
                validationType = ValidationType.CSV;
                break;
            } else {
                validationType = ValidationType.UNDEFINED;
            }
        }
        
        log.debug("<{}> Validation type: {}", jobId, validationType);
        
        if (validationType == ValidationType.UNDEFINED || validationType ==  null) {
                throw new IllegalArgumentException("<"+jobId+"> Not supported data format.");
        }
        
        String logFileName = Utils.getLogFileName(uploadedFiles); 
        
        List<String> dataFileNames = new ArrayList<>();
        List<String> iliFileNames = new ArrayList<>();
        List<String> configFileNames = new ArrayList<>();

        if (validationType == ValidationType.INTERLIS) {            
            for (Path path : uploadedFiles) {
                String fileName = path.toFile().getAbsolutePath();
                String fileExtension = FilenameUtils.getExtension(fileName).toLowerCase();
                switch (fileExtension) {
                    case "xtf" -> dataFileNames.add(fileName); 
                    case "xml" -> dataFileNames.add(fileName); 
                    case "itf" -> dataFileNames.add(fileName); 
                    case "ili" -> iliFileNames.add(fileName);
                    case "ini" -> configFileNames.add(fileName); // Wobei hier mehrere eigentlich keinen Sinn ergibt.
                }
            }
            
            log.debug("<{}> Number of uploaded transfer files: {}", jobId, dataFileNames.size());
            log.debug("<{}> Number of uploaded ili files: {}", jobId, iliFileNames.size());
            log.debug("<{}> Number of uploaded config files: {}", jobId, configFileNames.size());
            
            jobScheduler.enqueue(jobIdUuid, () -> ilivalidatorService.validate(dataFileNames.toArray(new String[0]), logFileName, iliFileNames.toArray(new String[0]), configFileNames.toArray(new String[0])));
            log.debug("<{}> Job is being queued", jobId);
        } else if (validationType == ValidationType.CSV) {
            for (Path path : uploadedFiles) {
                log.debug("<{}> File name: {}", jobId, path.toFile().getName());

                String fileName = path.toFile().getAbsolutePath();
                String fileExtension = FilenameUtils.getExtension(fileName).toLowerCase();
                switch (fileExtension) {
                    case "csv" -> dataFileNames.add(fileName); 
                    case "ili" -> iliFileNames.add(fileName);
                    case "ini" -> configFileNames.add(fileName);
                }                
            }
            
            log.debug("<{}> Number of uploaded csv files: {}", jobId, dataFileNames.size());
            log.debug("<{}> Number of uploaded ili files: {}", jobId, iliFileNames.size());
            log.debug("<{}> Number of uploaded config files: {}", jobId, configFileNames.size());

            jobScheduler.enqueue(jobIdUuid, () -> csvvalidatorService.validate(dataFileNames.toArray(new String[0]), logFileName, iliFileNames.toArray(new String[0]), configFileNames.toArray(new String[0])));
            log.debug("<{}> Job is being queued", jobId);
        }

        return ResponseEntity
                .accepted()
                .header("Operation-Location", getHost()+"/api/jobs/"+jobId)
                .body(null);        
    }
    
    @DeleteMapping("/api/jobs/{jobId}") 
    public ResponseEntity<?> deleteJobById(@PathVariable String jobId) {
        int ret = storageProvider.deletePermanently(UUID.fromString(jobId));
        if (ret==1) {
            JobResponse jobResponse = new JobResponse(
                    null,
                    null, 
                    null,
                    JOB_DELETED,
                    null, 
                    null, 
                    null
                    );
            return ResponseEntity.ok().body(jobResponse);
        } else {
            JobResponse jobResponse = new JobResponse(
                    null,
                    null, 
                    null,
                    JOB_NOT_FOUND,
                    null, 
                    null, 
                    null
                    );
            return ResponseEntity.badRequest().body(jobResponse);
        }
    }
    
    @GetMapping("/api/jobs/{jobId}")
    public ResponseEntity<?> getJobById(@PathVariable String jobId) {
        try {
            Job job = storageProvider.getJobById(UUID.fromString(jobId));        
            String logFileName = job.getJobDetails().getJobParameters().get(1).getObject().toString();  

            String logFileLocation = null;
            String xtfLogFileLocation = null;
            String validationResult = null;
            if (job.getJobState().getName().equals(StateName.SUCCEEDED)) {
                logFileLocation = Utils.fixUrl(getHost() + "/" + LOG_ENDPOINT + "/" + Utils.getLogFileUrlPathElement(logFileName));
                xtfLogFileLocation = logFileLocation + ".xtf"; 
                
                try {
                    // JobResult nur bei Pro-Version. Darum handgestrickt.
                    String content = Files.readString(Paths.get(logFileName));
                    if (content.contains("...validation done")) {
                        validationResult = ValidationResult.SUCCEEDED.toString();
                    } else if (content.contains("...validation failed")) {
                        validationResult = ValidationResult.FAILED.toString();
                    } else if (content.contains("Error")) {
                        validationResult = ValidationResult.FAILED.toString();                    
                    } else {
                        validationResult = ValidationResult.UNKNOWN.toString();
                    }
                } catch (IOException e) {
                    validationResult = ValidationResult.UNKNOWN.toString();
                    e.printStackTrace();
                }
            } else {
                log.debug("<{}> Status request from client: {}", jobId, job.getJobState().getName());
            }

            String message = null;
            JobResponse jobResponse = new JobResponse(
                    LocalDateTime.ofInstant(job.getCreatedAt(), ZoneId.systemDefault()),
                    LocalDateTime.ofInstant(job.getUpdatedAt(), ZoneId.systemDefault()), 
                    job.getState().name(),
                    message,
                    validationResult, 
                    logFileLocation, 
                    xtfLogFileLocation
                    );
            
            if (jobResponse.status().equals(StateName.SUCCEEDED.toString())) {
                return ResponseEntity.ok().body(jobResponse);
            } else if (jobResponse.status().equals(StateName.FAILED.toString())) {
                // Jobrunr-Api erlaubt aus mir nicht auf den Stacktrace zuzugreifen, der aufgetreten ist.
                // Eventuell ginge es mit der Pro-Version. Oder man liest es selber aus der DB.
                return ResponseEntity.badRequest().body(jobResponse);
            } else {
                return ResponseEntity.ok().header("Retry-After", "30").body(jobResponse);            
            }
        } catch (JobNotFoundException e) {
            JobResponse jobResponse = new JobResponse(
                    null,
                    null, 
                    null,
                    JOB_NOT_FOUND,
                    null, 
                    null, 
                    null
                    );
            return ResponseEntity.badRequest().body(jobResponse);
        }
    }
    
    private String getHost() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
