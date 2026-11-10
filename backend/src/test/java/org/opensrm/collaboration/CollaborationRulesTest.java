package org.opensrm.collaboration;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.opensrm.foundation.ApiProblem;
import static org.junit.jupiter.api.Assertions.*;
class CollaborationRulesTest {
 @Test void exactPartialBudget() {CollaborationRules.capacity(new BigDecimal("100"),new BigDecimal("60"),new BigDecimal("40"));assertThrows(ApiProblem.class,()->CollaborationRules.capacity(new BigDecimal("100"),new BigDecimal("60"),new BigDecimal("40.000001")));}
 @Test void pendingReservationsCount() {assertThrows(ApiProblem.class,()->CollaborationRules.capacity(new BigDecimal("100"),new BigDecimal("80"),new BigDecimal("21")));}
 @Test void zeroAndExcessPrecisionFail() {assertThrows(ApiProblem.class,()->CollaborationRules.quantity("0"));assertThrows(ApiProblem.class,()->CollaborationRules.quantity("1.0000001"));assertThrows(ApiProblem.class,()->CollaborationRules.quantity("10000000000000"));}
 @Test void decimalIsNeverRounded() {assertEquals("0.000001",CollaborationRules.quantity("0.000001").toPlainString());}
 @Test void selfApprovalFailsRegardlessOfRole() {UUID actor=UUID.randomUUID();assertThrows(ApiProblem.class,()->CollaborationRules.independentApprover(actor,actor));CollaborationRules.independentApprover(actor,UUID.randomUUID());}
}
