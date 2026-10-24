package com.tournatest.loader.protocol;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class UpdateWorkflowTest {
    @Test public void normalPreparationRequiresStartupMessagesWithoutSendingFirmware() throws Exception {
        ProtocolTest.FakeTime time = new ProtocolTest.FakeTime();
        ProtocolTest.FakePort port = new ProtocolTest.FakePort(time);
        assertThrows(IOException.class, () -> UpdateWorkflow.checkDevice(false, () -> port, time));
        assertTrue(port.writes.isEmpty());
    }

    @Test public void forcePreparationChecksUsbWithoutWaitingForStartupOrResettingDevice() throws Exception {
        ProtocolTest.FakeTime time = new ProtocolTest.FakeTime();
        ProtocolTest.FakePort port = new ProtocolTest.FakePort(time);
        UpdateWorkflow.checkDevice(true, () -> port, time);
        assertEquals(0, time.now);
        assertEquals("", port.dtr.toString());
        assertTrue(port.writes.isEmpty());
    }

    @Test public void forcePreparationDoesNotBypassUsbPermissionOrOpenFailures() {
        assertThrows(IOException.class, () -> UpdateWorkflow.checkDevice(true,
                () -> { throw new IOException("USB access was not granted."); }, new ProtocolTest.FakeTime()));
    }

    @Test public void forceFlashWorksWithoutAnyStartupMessagesAndSkipsRestartGate() throws Exception {
        ProtocolTest.FakeTime time = new ProtocolTest.FakeTime();
        ProtocolTest.FakePort port = new ProtocolTest.FakePort(time);
        AtomicInteger opens = new AtomicInteger();
        FirmwarePackage firmware = FirmwarePackageTest.realFirmware();
        UpdateWorkflow.Completion result = UpdateWorkflow.install(firmware, true,
                () -> { opens.incrementAndGet(); return port; }, time, (message, percent) -> {});
        assertEquals(UpdateWorkflow.Completion.TRANSFER_COMPLETE, result);
        assertEquals(1, opens.get());
        assertArrayEquals(firmware.application(), port.received.toByteArray());
        assertEquals(1, port.stops);
    }

    @Test public void normalFlashChecksBeforeTransferAndVerifiesRestart() throws Exception {
        ProtocolTest.FakeTime time = new ProtocolTest.FakeTime();
        ProtocolTest.FakePort transfer = new ProtocolTest.FakePort(time);
        ProtocolTest.FakePort restart = new ProtocolTest.FakePort(time);
        transfer.text = restart.text = "Initializing channels".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger opens = new AtomicInteger();
        UpdateWorkflow.Completion result = UpdateWorkflow.install(FirmwarePackageTest.realFirmware(), false,
                () -> opens.getAndIncrement() == 0 ? transfer : restart, time, (message, percent) -> {});
        assertEquals(UpdateWorkflow.Completion.RESTART_CONFIRMED, result);
        assertEquals(2, opens.get());
        assertEquals(1, transfer.stops);
        assertTrue(restart.writes.isEmpty());
    }

    @Test public void normalFlashStillRefusesAnUnidentifiedDevice() throws Exception {
        ProtocolTest.FakeTime time = new ProtocolTest.FakeTime();
        ProtocolTest.FakePort port = new ProtocolTest.FakePort(time);
        assertThrows(IOException.class, () -> UpdateWorkflow.install(FirmwarePackageTest.realFirmware(), false,
                () -> port, time, (message, percent) -> {}));
        assertTrue(port.writes.isEmpty());
    }

    @Test public void forceFlashStillFailsWhenBootloaderDoesNotAcknowledge() throws Exception {
        ProtocolTest.FakeTime time = new ProtocolTest.FakeTime();
        ProtocolTest.FakePort port = new ProtocolTest.FakePort(time);
        port.suppressAllAcks = true;
        assertThrows(IOException.class, () -> UpdateWorkflow.install(FirmwarePackageTest.realFirmware(), true,
                () -> port, time, (message, percent) -> {}));
        assertEquals(3, port.writes.size());
        assertEquals(0, port.stops);
    }
}
