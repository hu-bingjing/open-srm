package org.opensrm.notification;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class NotificationWorker {
 private final NotificationService service;
 public NotificationWorker(NotificationService service) {this.service=service;}
 @Scheduled(fixedDelayString="${opensrm.notification.delay:1000}",initialDelayString="${opensrm.notification.initial-delay:3000}") public void run() {
  try {service.tick();}catch(Exception error){org.slf4j.LoggerFactory.getLogger(NotificationWorker.class).error("event=notification_dispatch_rolled_back type={}",error.getClass().getSimpleName());}
 }
}
