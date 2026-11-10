package org.opensrm.identity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StartupGuardTest {
 @Test void globalErpWriteSwitchCannotBeEnabledEvenInTestEnvironment(){
  var identity=mock(IdentityService.class);
  var guard=new StartupGuard(identity,"test",java.util.UUID.randomUUID().toString(),"sap-readonly",true,false,java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString());
  assertThrows(IllegalStateException.class,()->guard.run(null));verifyNoInteractions(identity);
 }
}
