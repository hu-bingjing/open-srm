package org.opensrm.integration;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
@Component @EnableScheduling
public class SyncWorker {
    private final SyncService service;
    public SyncWorker(SyncService service) { this.service=service; }
    @Scheduled(fixedDelay=500,initialDelay=2000) public void run() {
        try {service.tick();} catch(Exception error) {org.slf4j.LoggerFactory.getLogger(SyncWorker.class).error("event=sync_page_rolled_back type={}",error.getClass().getSimpleName());}
    }
}
