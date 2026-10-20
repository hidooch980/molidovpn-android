package com.molido.vpn;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ConnectionDiagnosisTest {
    @Test public void offlineOverridesOldLogs() {
        assertEquals(ConnectionDiagnosis.Finding.OFFLINE,
            ConnectionDiagnosis.inspect(false, false, "", 10, Arrays.asList("timeout")));
    }
    @Test public void activeSessionIgnoresOldFailure() {
        assertEquals(ConnectionDiagnosis.Finding.ACTIVE,
            ConnectionDiagnosis.inspect(true, true, "", 10, Arrays.asList("UnknownHost")));
    }
    @Test public void selectedCountryIsExplained() {
        assertEquals(ConnectionDiagnosis.Finding.COUNTRY,
            ConnectionDiagnosis.inspect(true, false, "DE", 10, Collections.emptyList()));
    }
    @Test public void dnsAndTimeoutAreDistinguished() {
        assertEquals(ConnectionDiagnosis.Finding.DNS,
            ConnectionDiagnosis.inspect(true, false, "", 10, Arrays.asList("unable to resolve")));
        assertEquals(ConnectionDiagnosis.Finding.TIMEOUT,
            ConnectionDiagnosis.inspect(true, false, "", 10, Arrays.asList("timed out")));
    }
}
