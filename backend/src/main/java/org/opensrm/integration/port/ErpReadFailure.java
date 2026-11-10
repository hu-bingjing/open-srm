package org.opensrm.integration.port;
/** Safe transport classification. Never carry remote messages or credentials. */
public class ErpReadFailure extends RuntimeException {
 private final String code;private final long retryAfterSeconds;
 public ErpReadFailure() {this("ERP_READ_UNAVAILABLE");}
 public ErpReadFailure(String code) {this(code,0);}
 public ErpReadFailure(String code,long retryAfterSeconds) {super(code);if(!code.matches("[A-Z_]{3,80}"))throw new IllegalArgumentException("Unsafe failure code");this.code=code;this.retryAfterSeconds=retryAfterSeconds;}
 public String code() {return code;}
 public long retryAfterSeconds() {return retryAfterSeconds;}
}
