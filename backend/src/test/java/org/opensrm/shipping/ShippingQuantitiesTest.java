package org.opensrm.shipping;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.opensrm.foundation.ApiProblem;
import static org.junit.jupiter.api.Assertions.*;

class ShippingQuantitiesTest {
    private static BigDecimal q(String value) { return new BigDecimal(value); }
    private static void equal(String expected, BigDecimal value) { assertEquals(0,q(expected).compareTo(value)); }
    @Test void asnDoesNotCreateReceipt() {
        equal("70",ShippingQuantities.available(q("100"),q("100"),q("0"),ShippingQuantities.transit(q("30"),q("0"))));
    }
    @Test void allocatedReceiptMovesTransitWithoutIncreasingAvailable() {
        equal("10",ShippingQuantities.transit(q("30"),q("20")));
        equal("70",ShippingQuantities.available(q("100"),q("100"),q("20"),q("10")));
    }
    @Test void receiptWithoutAsnConservativelyReducesAvailable() {
        equal("60",ShippingQuantities.available(q("100"),q("100"),q("10"),q("30")));
    }
    @Test void confirmationAndPlanEachLimitShipment() {
        equal("30",ShippingQuantities.available(q("100"),q("60"),q("20"),q("10")));
        assertThrows(ApiProblem.class,()->ShippingQuantities.shipment(q("31"),q("30"),q("100"),q("0")));
        assertThrows(ApiProblem.class,()->ShippingQuantities.shipment(q("1"),q("30"),q("30"),q("30")));
    }
    @Test void authoritativeOverReceiptOrShrinkHasNoNegativeAvailability() {
        equal("0",ShippingQuantities.available(q("15"),q("60"),q("20"),q("10")));
    }
    @Test void partialAndFullReversalCannotExceedOriginal() {
        equal("15",ShippingQuantities.reverse(q("30"),q("10"),q("5")));
        equal("0",ShippingQuantities.reverse(q("30"),q("10"),q("20")));
        assertThrows(ApiProblem.class,()->ShippingQuantities.reverse(q("30"),q("10"),q("20.000001")));
    }
    @Test void reversalRestoresTransitAndDoesNotPermitReshippingPlan() {
        equal("20",ShippingQuantities.transit(q("30"),q("10")));
        equal("70",ShippingQuantities.available(q("100"),q("100"),q("10"),q("20")));
        assertThrows(ApiProblem.class,()->ShippingQuantities.shipment(q("1"),q("70"),q("30"),q("30")));
    }
    @Test void invalidAllocationAndNonpositiveOrImpreciseShipmentFail() {
        assertThrows(ApiProblem.class,()->ShippingQuantities.transit(q("10"),q("11")));
        for(String v:new String[]{"0","-1","0.0000001","10000000000000"})
            assertThrows(ApiProblem.class,()->ShippingQuantities.positive(q(v)));
        ShippingQuantities.shipment(q("0.000001"),q("0.000001"),q("1"),q("0"));
    }
}
