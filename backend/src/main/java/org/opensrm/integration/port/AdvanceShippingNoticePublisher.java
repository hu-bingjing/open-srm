package org.opensrm.integration.port;
public interface AdvanceShippingNoticePublisher { ErpData.PublishResult publishAsn(ErpData.PublishCommand command,String idempotencyKey); }
