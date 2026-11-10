package org.opensrm.collaboration;
import java.math.BigDecimal;
import java.util.UUID;
import org.opensrm.foundation.ApiProblem;
public final class CollaborationRules {
 private CollaborationRules() { }
 public static BigDecimal quantity(String value) {var q=new BigDecimal(value);if(q.signum()<=0 || q.scale()>6 || q.precision()-q.scale()>13) throw new ApiProblem(422,"INVALID_QUANTITY");return q;}
 public static void capacity(BigDecimal ordered,BigDecimal reserved,BigDecimal requested) {if(reserved.add(requested).compareTo(ordered)>0) throw new ApiProblem(409,"QUANTITY_EXCEEDED");}
 public static void independentApprover(UUID actor,UUID requester) {if(actor.equals(requester)) throw new ApiProblem(403,"SELF_APPROVAL_FORBIDDEN");}
}
