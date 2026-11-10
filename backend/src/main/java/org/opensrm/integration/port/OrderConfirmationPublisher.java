package org.opensrm.integration.port;
public interface OrderConfirmationPublisher { ErpData.PublishResult publishConfirmation(ErpData.PublishCommand command,String idempotencyKey); }
