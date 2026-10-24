package com.tournatest.loader;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.tournatest.loader.protocol.SerialTransport;

import java.io.IOException;

final class UsbSerialConnection implements SerialTransport {
    private final UsbSerialPort port;

    static UsbSerialConnection open(UsbManager manager, String deviceName) throws IOException {
        UsbDevice device = manager.getDeviceList().get(deviceName);
        if (device == null) throw new IOException("The USB device was disconnected. Reconnect and scan again.");
        if (!manager.hasPermission(device)) throw new IOException("USB access was not granted.");
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (!(driver instanceof Cp21xxSerialDriver) || driver.getPorts().size() != 1) {
            throw new IOException("Select the TournaTest CP210x USB interface.");
        }
        UsbDeviceConnection usb = manager.openDevice(device);
        if (usb == null) throw new IOException("Cannot open USB device. Close other USB serial apps and retry.");
        UsbSerialPort port = driver.getPorts().get(0);
        try {
            port.open(usb);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setFlowControl(UsbSerialPort.FlowControl.NONE);
            port.setRTS(false);
            return new UsbSerialConnection(port);
        } catch (IOException | RuntimeException e) {
            try { port.close(); } catch (IOException ignored) { /* May not have opened. */ }
            usb.close();
            throw e;
        }
    }

    private UsbSerialConnection(UsbSerialPort port) { this.port = port; }

    @Override public int read(byte[] buffer, int timeoutMs) throws IOException { return port.read(buffer, timeoutMs); }
    @Override public void write(byte[] bytes, int timeoutMs) throws IOException { port.write(bytes, timeoutMs); }
    @Override public void setDtr(boolean enabled) throws IOException { port.setDTR(enabled); }
    @Override public void discardInput() throws IOException { port.purgeHwBuffers(false, true); }
    @Override public void close() throws IOException { port.close(); }
}
