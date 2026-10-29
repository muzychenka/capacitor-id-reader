package dev.idreader.capacitor;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;

import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.ActivityCallback;
import androidx.activity.result.ActivityResult;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.IsoDepCardService;

import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.CardAccessFile;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.SecurityInfo;
import org.jmrtd.lds.icao.COMFile;
import org.jmrtd.lds.LDSFileUtil;
import org.jmrtd.lds.icao.DG1File;
import org.jmrtd.lds.icao.DG2File;
import org.jmrtd.lds.iso19794.FaceInfo;
import org.jmrtd.lds.iso19794.FaceImageInfo;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import org.jmrtd.lds.icao.MRZInfo;

import java.io.InputStream;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "NfcIdReader")
public class NfcIdReaderPlugin extends Plugin {

    private static final String ERROR_UNAVAILABLE = "NFC_UNAVAILABLE";
    private static final String ERROR_DISABLED = "NFC_DISABLED";
    private static final String ERROR_CANCELLED = "NFC_CANCELLED";
    private static final String ERROR_WRONG_KEY = "NFC_WRONG_KEY";
    private static final String ERROR_TAG_LOST = "NFC_TAG_LOST";
    private static final String ERROR_READ_FAILED = "NFC_READ_FAILED";

    private static final int READER_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;

    // A chip read takes several seconds of uninterrupted contact; the default timeout aborts sooner.
    private static final int ISO_DEP_TIMEOUT_MS = 20000;

    private NfcAdapter nfcAdapter;
    private volatile PluginCall pendingCall;
    private IsoDep activeIsoDep;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final Object pendingLock = new Object();
    private final AtomicBoolean reading = new AtomicBoolean(false);

    @Override
    public void load() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(getContext());
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", nfcAdapter != null);
        result.put("enabled", nfcAdapter != null && nfcAdapter.isEnabled());
        call.resolve(result);
    }

    @PluginMethod
    public void scanMrz(PluginCall call) {
        if (!scanning.compareAndSet(false, true)) {
            call.reject("A scan is already in progress", "NFC_BUSY");
            return;
        }
        Intent intent = new Intent(getContext(), MrzScannerActivity.class);
        intent.putExtra("prompt", call.getString("prompt"));
        intent.putExtra("cancelLabel", call.getString("cancelLabel"));
        try {
            startActivityForResult(call, intent, "mrzResult");
        } catch (RuntimeException error) {
            scanning.set(false);
            call.reject("Could not open camera scanner", "CAMERA_UNAVAILABLE");
        }
    }

    @ActivityCallback
    private void mrzResult(PluginCall call, ActivityResult result) {
        scanning.set(false);
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            String code = data == null ? null : data.getStringExtra("error_code");
            if (code == null) call.reject("MRZ scan cancelled", ERROR_CANCELLED);
            else call.reject("MRZ camera scan failed", code);
            return;
        }
        String[] lines = data.getStringArrayExtra(MrzScannerActivity.EXTRA_LINES);
        if (lines == null || lines.length < 2) {
            call.reject("MRZ scan failed", ERROR_READ_FAILED);
            return;
        }
        JSArray values = new JSArray();
        for (String line : lines) values.put(line);
        JSObject output = new JSObject();
        output.put("lines", values);
        call.resolve(output);
    }

    @PluginMethod
    public void readDocument(PluginCall call) {
        if (nfcAdapter == null) {
            call.reject("This device has no NFC hardware", ERROR_UNAVAILABLE);
            return;
        }

        if (!nfcAdapter.isEnabled()) {
            call.reject("NFC is turned off in system settings", ERROR_DISABLED);
            return;
        }

        JSArray requested = call.getArray("dataGroups");
        boolean dg2Requested = false;
        if (requested != null) {
            for (int i = 0; i < requested.length(); i++) {
                String group = requested.optString(i);
                if ("DG2".equalsIgnoreCase(group)) dg2Requested = true;
                else if (!"DG1".equalsIgnoreCase(group)) {
                    call.reject("Only DG1 and DG2 are supported", "NFC_UNSUPPORTED_DATA_GROUP");
                    return;
                }
            }
        }

        final String documentNumber = call.getString("documentNumber");
        final String dateOfBirth = call.getString("dateOfBirth");
        final String dateOfExpiry = call.getString("dateOfExpiry");

        if (documentNumber == null || !documentNumber.matches("[A-Z0-9<]{1,9}") ||
            dateOfBirth == null || !dateOfBirth.matches("[0-9]{6}") ||
            dateOfExpiry == null || !dateOfExpiry.matches("[0-9]{6}")) {
            call.reject("documentNumber, dateOfBirth and dateOfExpiry are required", ERROR_WRONG_KEY);
            return;
        }

        synchronized (pendingLock) {
            if (pendingCall != null || reading.get()) {
                call.reject("A read is already in progress", "NFC_BUSY");
                return;
            }
            pendingCall = call;
        }

        final boolean includeDG2 = dg2Requested;
        final BACKeySpec key = new BACKey(documentNumber, dateOfBirth, dateOfExpiry);
        final Activity activity = getActivity();
        if (activity == null) {
            if (finish(call)) call.reject("No foreground activity", ERROR_UNAVAILABLE);
            return;
        }

        activity.runOnUiThread(() -> {
            if (pendingCall != call) return;
            try {
                nfcAdapter.enableReaderMode(
                    activity,
                    tag -> onTagDiscovered(tag, key, call, includeDG2),
                    READER_FLAGS,
                    buildReaderExtras()
                );
            } catch (RuntimeException error) {
                if (finish(call)) call.reject("Could not start NFC reader", ERROR_READ_FAILED);
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelPending();
        call.resolve();
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        // Reader mode cannot outlive the foreground activity, so a backgrounded read is a lost read.
        cancelPending();
    }

    private Bundle buildReaderExtras() {
        Bundle extras = new Bundle();
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000);
        return extras;
    }

    private void onTagDiscovered(Tag tag, BACKeySpec key, PluginCall call, boolean includeDG2) {
        if (pendingCall != call) {
            return;
        }

        if (!reading.compareAndSet(false, true)) return;
        if (pendingCall != call) {
            reading.set(false);
            return;
        }

        IsoDep isoDep = IsoDep.get(tag);

        if (isoDep == null) {
            if (finish(call)) call.reject("This tag is not an ISO-DEP chip", ERROR_READ_FAILED);
            reading.set(false);
            return;
        }

        synchronized (pendingLock) {
            if (pendingCall != call) { reading.set(false); return; }
            activeIsoDep = isoDep;
        }
        JSObject result = null;
        String errorCode = null;
        String errorMessage = null;
        try {
            isoDep.setTimeout(ISO_DEP_TIMEOUT_MS);
            result = readMrz(isoDep, key, call, includeDG2);
        } catch (Dg2ReadException error) {
            errorCode = "NFC_DG2_READ_FAILED";
            errorMessage = "Could not read a supported DG2 face image";
        } catch (android.nfc.TagLostException error) {
            errorCode = ERROR_TAG_LOST;
            errorMessage = "The document was moved away before the read finished";
        } catch (Exception error) {
            errorCode = ERROR_READ_FAILED;
            errorMessage = "Could not read the chip";
        } finally {
            try { isoDep.close(); } catch (Exception ignored) { }
        }
        // Remove the old request and release the read gate together before resolving it.
        boolean completed;
        synchronized (pendingLock) {
            activeIsoDep = null;
            completed = finish(call);
            reading.set(false);
        }
        if (completed) {
            if (errorCode == null) call.resolve(result);
            else call.reject(errorMessage, errorCode);
        }
    }

    private JSObject readMrz(IsoDep isoDep, BACKeySpec key, PluginCall call, boolean includeDG2) throws Exception {
        CardService cardService = new IsoDepCardService(isoDep);

        PassportService service = new PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false
        );
        try {
            cardService.open();
            if (pendingCall != call) throw new java.io.IOException("Read cancelled");
            service.open();
            boolean paceSucceeded = tryPace(service, key);
            service.sendSelectApplet(paceSucceeded);
            if (!paceSucceeded) {
                service.doBAC(key);
            }
            JSObject result;
            try (InputStream dg1Stream = service.getInputStream(PassportService.EF_DG1)) {
                MRZInfo mrzInfo = new DG1File(dg1Stream).getMRZInfo();
                result = toResult(mrzInfo);
            }
            // COM is optional metadata. An unreadable COM must not prevent DG1/DG2 reading.
            try (InputStream comStream = service.getInputStream(PassportService.EF_COM)) {
                JSArray available = new JSArray();
                for (int group : LDSFileUtil.getDataGroupNumbers(new COMFile(comStream))) available.put("DG" + group);
                result.put("availableDataGroups", available);
            } catch (android.nfc.TagLostException error) { throw error; }
              catch (Exception ignored) { /* Some documents do not expose COM. */ }
            {

                if (includeDG2) {
                    try (InputStream dg2Stream = service.getInputStream(PassportService.EF_DG2)) {
                        DG2File dg2 = new DG2File(dg2Stream);
                        FaceImageInfo image = null;
                        for (FaceInfo face : dg2.getFaceInfos()) {
                            if (!face.getFaceImageInfos().isEmpty()) { image = face.getFaceImageInfos().get(0); break; }
                        }
                        if (image == null || image.getImageLength() <= 0 || image.getImageLength() > 8 * 1024 * 1024) throw new Dg2ReadException();
                        String mime = image.getMimeType();
                        if (!"image/jpeg".equals(mime) && !"image/jp2".equals(mime) && !"image/jpeg2000".equals(mime)) throw new Dg2ReadException();
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (InputStream imageStream = image.getImageInputStream()) {
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = imageStream.read(buffer)) != -1) {
                                if (bytes.size() + count > 8 * 1024 * 1024) throw new Dg2ReadException();
                                bytes.write(buffer, 0, count);
                            }
                        }
                        if (bytes.size() != image.getImageLength()) throw new Dg2ReadException();
                        JSObject photo = new JSObject();
                        photo.put("base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
                        photo.put("mimeType", "image/jpeg".equals(mime) ? mime : "image/jp2");
                        result.put("faceImage", photo);
                        result.put("readDataGroups", new JSArray().put("DG1").put("DG2"));
                    } catch (android.nfc.TagLostException error) { throw error; }
                      catch (Exception error) { throw new Dg2ReadException(); }
                }
                return result;
            }
        } finally {
            try { service.close(); } finally { cardService.close(); }
        }
    }

    private static final class Dg2ReadException extends Exception { }

    private boolean tryPace(PassportService service, BACKeySpec key) throws Exception {
        CardAccessFile cardAccessFile;
        try {
            try (InputStream stream = service.getInputStream(PassportService.EF_CARD_ACCESS)) {
                cardAccessFile = new CardAccessFile(stream);
            }
        } catch (Exception error) {
            // Legacy BAC-only documents may not have EF.CardAccess. Do not downgrade after
            // an attempted PACE authentication, which may have failed for another reason.
            return false;
        }
        Collection<SecurityInfo> securityInfos = cardAccessFile.getSecurityInfos();
        for (SecurityInfo securityInfo : securityInfos) {
            if (securityInfo instanceof PACEInfo) {
                PACEInfo paceInfo = (PACEInfo) securityInfo;
                service.doPACE(key, paceInfo.getObjectIdentifier(), PACEInfo.toParameterSpec(paceInfo.getParameterId()), null);
                return true;
            }
        }
        return false;
    }

    private JSObject toResult(MRZInfo mrzInfo) {
        JSObject result = new JSObject();
        result.put("documentCode", mrzInfo.getDocumentCode());
        result.put("documentNumber", mrzInfo.getDocumentNumber());
        result.put("issuingState", mrzInfo.getIssuingState());
        result.put("nationality", mrzInfo.getNationality());
        result.put("primaryIdentifier", normalize(mrzInfo.getPrimaryIdentifier()));
        result.put("secondaryIdentifier", normalize(mrzInfo.getSecondaryIdentifier()));
        result.put("dateOfBirth", mrzInfo.getDateOfBirth());
        result.put("dateOfExpiry", mrzInfo.getDateOfExpiry());
        result.put("gender", mrzInfo.getGender() == null ? "" : mrzInfo.getGender().toString());
        result.put("personalNumber", mrzInfo.getPersonalNumber());
        result.put("readDataGroups", new com.getcapacitor.JSArray().put("DG1"));
        result.put("authenticityVerified", false);
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("<", " ").trim();
    }

    private void cancelPending() {
        synchronized (pendingLock) {
            PluginCall call = pendingCall;
            if (call != null && finish(call)) {
                if (activeIsoDep != null) {
                    try { activeIsoDep.close(); } catch (Exception ignored) { }
                }
                call.reject("The read was cancelled", ERROR_CANCELLED);
            }
        }
    }

    private boolean finish(PluginCall call) {
        synchronized (pendingLock) {
            if (call == null || pendingCall != call) return false;
            pendingCall = null;
        }

        Activity activity = getActivity();

        if (nfcAdapter != null && activity != null) {
            activity.runOnUiThread(() -> {
                if (pendingCall == null) {
                    try { nfcAdapter.disableReaderMode(activity); } catch (RuntimeException ignored) { }
                }
            });
        }
        return true;
    }
}
