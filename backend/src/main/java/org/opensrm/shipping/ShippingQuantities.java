package org.opensrm.shipping;

import java.math.BigDecimal;
import org.opensrm.foundation.ApiProblem;

/** Exact quantity invariants; callers must lock and read the cross-version ledger in one transaction. */
public final class ShippingQuantities {
    private ShippingQuantities() { }
    private static BigDecimal nonnegative(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 6 || value.precision() - value.scale() > 13)
            throw new ApiProblem(422, "INVALID_QUANTITY");
        return value;
    }
    public static BigDecimal positive(BigDecimal value) {
        nonnegative(value);
        if (value.signum() == 0) throw new ApiProblem(422, "INVALID_QUANTITY");
        return value;
    }
    public static BigDecimal transit(BigDecimal submitted, BigDecimal allocatedNetReceipt) {
        nonnegative(submitted); nonnegative(allocatedNetReceipt);
        if (allocatedNetReceipt.compareTo(submitted) > 0) throw new ApiProblem(409, "RECEIPT_ALLOCATION_EXCEEDED");
        return submitted.subtract(allocatedNetReceipt);
    }
    public static BigDecimal available(BigDecimal ordered, BigDecimal confirmed, BigDecimal netReceived, BigDecimal inTransit) {
        nonnegative(ordered); nonnegative(confirmed); nonnegative(netReceived); nonnegative(inTransit);
        return ordered.min(confirmed).subtract(netReceived).subtract(inTransit).max(BigDecimal.ZERO);
    }
    public static void shipment(BigDecimal requested, BigDecimal available, BigDecimal planQuantity, BigDecimal previouslySubmitted) {
        positive(requested); nonnegative(available); nonnegative(planQuantity); nonnegative(previouslySubmitted);
        if (requested.compareTo(available) > 0 || previouslySubmitted.add(requested).compareTo(planQuantity) > 0)
            throw new ApiProblem(409, "SHIPPING_QUANTITY_EXCEEDED");
    }
    public static BigDecimal reverse(BigDecimal posted, BigDecimal alreadyReversed, BigDecimal requested) {
        positive(posted); nonnegative(alreadyReversed); positive(requested);
        if (alreadyReversed.add(requested).compareTo(posted) > 0) throw new ApiProblem(409, "REVERSAL_QUANTITY_EXCEEDED");
        return posted.subtract(alreadyReversed).subtract(requested);
    }
}
