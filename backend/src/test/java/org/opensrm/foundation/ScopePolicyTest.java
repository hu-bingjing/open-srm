package org.opensrm.foundation;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopePolicyTest {
    final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),pa=UUID.randomUUID(),pb=UUID.randomUUID(),oa=UUID.randomUUID(),ob=UUID.randomUUID(),sa=UUID.randomUUID(),sb=UUID.randomUUID();
    @Test void fullTupleAuthorizes() {
        assertTrue(ScopePolicy.allows("order.read",new ScopePolicy.Scope(a,pa,oa,sa),List.of(new ScopePolicy.Grant("order.read",a,pa,oa)),sa));
    }
    @Test void cannotSpliceGrants() {
        assertFalse(ScopePolicy.allows("order.read",new ScopePolicy.Scope(a,pb,ob,sa),List.of(new ScopePolicy.Grant("order.read",a,pa,oa),new ScopePolicy.Grant("order.read",b,pb,ob)),sa));
    }
    @Test void crossSupplierDenied() {
        assertFalse(ScopePolicy.allows("order.read",new ScopePolicy.Scope(a,pa,oa,sb),List.of(new ScopePolicy.Grant("order.read",a,null,null)),sa));
    }
    @Test void crossCompanyDenied() {
        assertFalse(ScopePolicy.allows("order.read",new ScopePolicy.Scope(b,pb,ob,sa),List.of(new ScopePolicy.Grant("order.read",a,null,null)),null));
    }
    @Test void companyWildcardDoesNotRequireAllPlants() {
        assertTrue(ScopePolicy.allows("order.read",new ScopePolicy.Scope(a,pa,oa,sa),List.of(new ScopePolicy.Grant("order.read",a,null,null)),null));
    }
    @Test void adminWithoutBusinessGrantDenied() {
        assertFalse(ScopePolicy.allows("order.read",new ScopePolicy.Scope(a,pa,oa,sa),List.of(),null));
    }
}
