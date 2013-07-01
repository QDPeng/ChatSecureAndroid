package info.guardianproject.otr;

import info.guardianproject.bouncycastle.util.encoders.Hex;
import info.guardianproject.otr.app.im.R;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

import net.java.otr4j.OtrKeyManager;
import net.java.otr4j.OtrKeyManagerListener;
import net.java.otr4j.OtrKeyManagerStore;
import net.java.otr4j.crypto.OtrCryptoEngineImpl;
import net.java.otr4j.crypto.OtrCryptoException;
import net.java.otr4j.session.SessionID;

import org.jivesoftware.smack.util.Base64;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

public class OtrAndroidKeyManagerImpl implements OtrKeyManager {

    private SimplePropertiesStore store;

    private OtrCryptoEngineImpl cryptoEngine;

    private final static String KEY_ALG = "DSA";
    private final static int KEY_SIZE = 1024;

    private static OtrAndroidKeyManagerImpl _instance;

    public static synchronized OtrAndroidKeyManagerImpl getInstance(Context context)
            throws IOException {
        if (_instance == null) {
            File f = new File(context.getApplicationContext().getFilesDir(), "otr_keystore");
            _instance = new OtrAndroidKeyManagerImpl(f);
        }

        return _instance;
    }

    private OtrAndroidKeyManagerImpl(File filepath) throws IOException {
        this.store = new SimplePropertiesStore(filepath);

        cryptoEngine = new OtrCryptoEngineImpl();
    }

    class SimplePropertiesStore implements OtrKeyManagerStore {
        private Properties mProperties = new Properties();
        private File mStoreFile;

        public SimplePropertiesStore(File storeFile) {
            mStoreFile = storeFile;
            mProperties.clear();

            load();
        }

        public SimplePropertiesStore(File storeFile, final String password) {
            OtrDebugLogger.log("Loading store from encrypted file");
            mStoreFile = storeFile;
            mProperties.clear();

            loadAES(password);
        }

        private void loadAES(final String password) {
            String decoded;
            try {
                decoded = AES_256_CBC.decrypt(mStoreFile, password);
                mProperties.load(new ByteArrayInputStream(decoded.getBytes()));
            } catch (IOException ioe) {
                OtrDebugLogger.log("Properties store error", ioe);
            }
        }

        private void load() {
            try {

                FileInputStream fis = new FileInputStream(mStoreFile);

                InputStream in = new BufferedInputStream(fis);
                try {
                    mProperties.load(in);
                } finally {
                    in.close();
                }
            } catch (FileNotFoundException fnfe) {
                OtrDebugLogger.log("Properties store file not found: First time?");
                mStoreFile.getParentFile().mkdirs();

                try {
                    save();
                } catch (Exception ioe) {
                    OtrDebugLogger.log("Properties store error", ioe);
                }

            } catch (IOException ioe) {
                OtrDebugLogger.log("Properties store error", ioe);
            }
        }
        
        public Properties getProperties ()
        {
            return mProperties;
        }

        public void setProperty(String id, boolean value) {
            mProperties.setProperty(id, "true");
            try {
                this.save();
            } catch (Exception e) {
                OtrDebugLogger.log("Properties store error", e);
            }
        }

        private void save() throws FileNotFoundException, IOException {

            //Log.d(TAG,"saving otr keystore to: " + filepath);

            FileOutputStream fos = new FileOutputStream(mStoreFile);

            mProperties.store(fos, null);

            fos.close();
        }

        private void saveAES (String password)
        {
            
        }
        
        public void setProperty(String id, byte[] value) {
            mProperties.setProperty(id, new String(Base64.encodeBytes(value)));

            try {
                this.save();
            } catch (Exception e) {
                OtrDebugLogger.log("store not saved", e);
            }
        }

        // Store as hex bytes
        public void setPropertyHex(String id, byte[] value) {
            mProperties.setProperty(id, new String(Hex.encode(value)));

            try {
                this.save();
            } catch (Exception e) {
                OtrDebugLogger.log("store not saved", e);
            }
        }

        public void removeProperty(String id) {
            mProperties.remove(id);

        }

        public byte[] getPropertyBytes(String id) {
            String value = mProperties.getProperty(id);

            if (value != null)
                return Base64.decode(value);
            return null;
        }

        // Load from hex bytes
        public byte[] getPropertyHexBytes(String id) {
            String value = mProperties.getProperty(id);

            if (value != null)
                return Hex.decode(value);
            return null;
        }

        public boolean getPropertyBoolean(String id, boolean defaultValue) {
            try {
                return Boolean.valueOf(mProperties.get(id).toString());
            } catch (Exception e) {
                return defaultValue;
            }
        }
    }

    private List<OtrKeyManagerListener> listeners = new Vector<OtrKeyManagerListener>();

    public void addListener(OtrKeyManagerListener l) {
        synchronized (listeners) {
            if (!listeners.contains(l))
                listeners.add(l);
        }
    }

    public void removeListener(OtrKeyManagerListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public void generateLocalKeyPair(SessionID sessionID) {
        if (sessionID == null)
            return;

        String accountID = sessionID.getAccountID();

        generateLocalKeyPair(accountID);
    }

    public void generateLocalKeyPair(String accountID) {

        OtrDebugLogger.log("generating local key pair for: " + accountID);

        KeyPair keyPair;
        try {

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALG);
            kpg.initialize(KEY_SIZE);

            keyPair = kpg.genKeyPair();
        } catch (NoSuchAlgorithmException e) {
            OtrDebugLogger.log("no such algorithm", e);
            return;
        }

        OtrDebugLogger.log("SUCCESS! generating local key pair for: " + accountID);

        // Store Public Key.
        PublicKey pubKey = keyPair.getPublic();
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(pubKey.getEncoded());

        this.store.setProperty(accountID + ".publicKey", x509EncodedKeySpec.getEncoded());

        // Store Private Key.
        PrivateKey privKey = keyPair.getPrivate();
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(privKey.getEncoded());

        this.store.setProperty(accountID + ".privateKey", pkcs8EncodedKeySpec.getEncoded());

        // Stash fingerprint for consistency.
        try {
            String fingerprintString = new OtrCryptoEngineImpl().getFingerprint(pubKey);
            this.store.setPropertyHex(accountID + ".fingerprint", Hex.decode(fingerprintString));
        } catch (OtrCryptoException e) {
            e.printStackTrace();
        }
    }
    
    public void importKeyStore(File filePath, String password, boolean overWriteExisting, boolean deleteImportedFile) throws IOException
    {
        SimplePropertiesStore storeNew = null;

        if (filePath.getName().endsWith(".ofcaes")) {
            //TODO implement GUI to get password via QR Code, and handle wrong password
            storeNew = new SimplePropertiesStore(filePath, password);
            deleteImportedFile = true; // once its imported, its no longer needed
        } else
            storeNew = new SimplePropertiesStore(filePath);
        
        Properties pNew = storeNew.getProperties();
        Enumeration<Object> enumKeys = pNew.keys();
        
        Object key;
        
        while (enumKeys.hasMoreElements())
        {
            key = enumKeys.nextElement();
            
            boolean hasKey = store.getProperties().containsKey(key);
            
        //    Log.d("OTR","importing key: " + key.toString());
            
            if (!hasKey || overWriteExisting)
                store.getProperties().put(key, pNew.get(key));
            
        }

        store.save();

        if (deleteImportedFile)
            filePath.delete();
    }

    public String getLocalFingerprint(SessionID sessionID) {
        return getLocalFingerprint(sessionID.getAccountID());
    }

    public String getLocalFingerprint(String userId) {
        KeyPair keyPair = loadLocalKeyPair(userId);

        if (keyPair == null)
            return null;

        PublicKey pubKey = keyPair.getPublic();

        try {
            String fingerprint = cryptoEngine.getFingerprint(pubKey);

            OtrDebugLogger.log("got fingerprint for: " + userId + "=" + fingerprint);

            return fingerprint;

        } catch (OtrCryptoException e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean isVerified(SessionID sessionID, String fingerprint) {
        if (sessionID == null)
            return false;

        String userId = sessionID.getUserID();
        String pubKeyVerifiedToken = buildPublicKeyVerifiedId(userId, fingerprint);

        return this.store.getPropertyBoolean(pubKeyVerifiedToken, false);
    }

    public boolean isVerifiedUser(String userId, String fingerprint) {
        if (userId == null)
            return false;

        String pubKeyVerifiedToken = buildPublicKeyVerifiedId(userId, fingerprint);

        return this.store.getPropertyBoolean(pubKeyVerifiedToken, false);
    }

    public KeyPair loadLocalKeyPair(SessionID sessionID) {
        if (sessionID == null)
            return null;

        String accountID = sessionID.getAccountID();
        return loadLocalKeyPair(accountID);
    }

    public KeyPair loadLocalKeyPair(String accountID) {

        // Load Private Key.

        byte[] b64PrivKey = this.store.getPropertyBytes(accountID + ".privateKey");
        if (b64PrivKey == null)
            return null;

        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(b64PrivKey);

        // Load Public Key.
        byte[] b64PubKey = this.store.getPropertyBytes(accountID + ".publicKey");
        if (b64PubKey == null)
            return null;

        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(b64PubKey);

        PublicKey publicKey;
        PrivateKey privateKey;

        // Generate KeyPair.
        KeyFactory keyFactory;
        try {
            keyFactory = KeyFactory.getInstance(KEY_ALG);
            publicKey = keyFactory.generatePublic(publicKeySpec);
            privateKey = keyFactory.generatePrivate(privateKeySpec);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
            return null;
        }

        return new KeyPair(publicKey, privateKey);
    }

    public PublicKey loadRemotePublicKey(SessionID sessionID) {

        return loadRemotePublicKeyFromStore(sessionID.getUserID());
    }

    public PublicKey loadRemotePublicKeyFromStore(String userID) {

        byte[] b64PubKey = this.store.getPropertyBytes(userID + ".publicKey");
        if (b64PubKey == null) {
            return null;

        }

        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(b64PubKey);

        // Generate KeyPair from spec
        KeyFactory keyFactory;
        try {
            keyFactory = KeyFactory.getInstance(KEY_ALG);

            return keyFactory.generatePublic(publicKeySpec);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
            return null;
        }
    }

    public String savePublicKey(SessionID sessionID, PublicKey pubKey) {
        if (sessionID == null)
            return null;

        String userId = sessionID.getUserID();
        
        String fingerprintString;
        
        try {
            fingerprintString = new OtrCryptoEngineImpl().getFingerprint(pubKey);
        } catch (OtrCryptoException e) {
            throw new RuntimeException(e);
        }

        byte[] fingerprintBytes = Hex.decode(fingerprintString);
        byte[] storedFingerprint = this.store.getPropertyHexBytes(userId + ".fingerprint");
        if (storedFingerprint == null || !storedFingerprint.equals(fingerprintBytes)) {
            this.store.setPropertyHex(userId + ".fingerprint", fingerprintBytes);
            X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(pubKey.getEncoded());

            this.store.setProperty(userId + ".publicKey", x509EncodedKeySpec.getEncoded());
        }
        // Stash the associated fingerprint.  This saves calculating it in the future
        // and is useful for transferring rosters to other apps.
        return fingerprintString;
    }

    public void unverify(SessionID sessionID, String fingerprint) {
        if (sessionID == null)
            return;

        if (!isVerified(sessionID, fingerprint))
            return;

        String userId = sessionID.getUserID();

        this.store.removeProperty(buildPublicKeyVerifiedId(userId, fingerprint));

        for (OtrKeyManagerListener l : listeners)
            l.verificationStatusChanged(sessionID, false);

    }

    public void unverifyUser(String userId, String remoteFingerprint) {
        if (userId == null)
            return;

        if (!isVerifiedUser(userId, remoteFingerprint))
            return;

        this.store.removeProperty(buildPublicKeyVerifiedId(userId, remoteFingerprint));

        //	for (OtrKeyManagerListener l : listeners)
        //	l.verificationStatusChanged(sessionID);

    }

    public void verify(SessionID sessionID, String fingerprint) {
        if (sessionID == null)
            return;

        if (this.isVerified(sessionID, fingerprint))
            return;

        String userId = sessionID.getUserID();

        this.store.setProperty(buildPublicKeyVerifiedId(userId, fingerprint), true);

        for (OtrKeyManagerListener l : listeners)
            l.verificationStatusChanged(sessionID, true);
    }

    public void remoteVerifiedUs(SessionID sessionID) {
        if (sessionID == null)
            return;

        for (OtrKeyManagerListener l : listeners)
            l.remoteVerifiedUs(sessionID);
    }

    private static String buildPublicKeyVerifiedId(String userId, String fingerprint) {
        return userId + "." + fingerprint + ".publicKey.verified";
    }

    public void verifyUser(String userId, String remoteFingerprint) {
        if (userId == null)
            return;

        if (this.isVerifiedUser(userId, remoteFingerprint))
            return;

        this.store
                .setProperty(buildPublicKeyVerifiedId(userId, remoteFingerprint), true);

        //for (OtrKeyManagerListener l : listeners)
        //l.verificationStatusChanged(userId);

    }

    public static boolean checkForKeyImport (Intent intent, Activity activity)
    {
        boolean doKeyStoreImport = false;
        
        // if otr_keystore.ofcaes is in the SDCard root, import it
        File otrKeystoreAES = new File(Environment.getExternalStorageDirectory(),
                "otr_keystore.ofcaes");
        if (otrKeystoreAES.exists()) {
            //Log.i(TAG, "found " + otrKeystoreAES + "to import");
            doKeyStoreImport = true;
            importOtrKeyStore(otrKeystoreAES, activity);
        }
        else if (intent.getData() != null)
        {
            Uri uriData = intent.getData();
            String path = null;
            
            if(uriData.getScheme() != null && uriData.getScheme().equals("file"))
            {
                path = uriData.toString().replace("file://", "");
            
                File file = new File(path);
                
                doKeyStoreImport = true;
                
                importOtrKeyStore(file, activity);
            }
        }
        
        return doKeyStoreImport;
    }
    
    
    public static void importOtrKeyStore (final File fileOtrKeyStore, final Activity activity)
    {
     
        try
        {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());

            prefs.edit().putString("keystoreimport", fileOtrKeyStore.getCanonicalPath()).commit();
        }
        catch (IOException ioe)
        {
            Log.e("TAG","problem importing key store",ioe);
            return;
        }

        Dialog.OnClickListener ocl = new Dialog.OnClickListener ()
        {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                
                //launch QR code intent
                IntentIntegrator.initiateScan(activity);
                
            }
        };
        

        new AlertDialog.Builder(activity).setTitle(R.string.confirm)
                  .setMessage(R.string.detected_Otr_keystore_import)
                  .setPositiveButton(R.string.yes, ocl) // default button
                  .setNegativeButton(R.string.no, null).setCancelable(true).show();
      
      
    }
    
    public static void importOtrKeyStoreWithPassword (final File fileOtrKeyStore, String password, Activity activity) throws IOException
    {

        OtrAndroidKeyManagerImpl oakm = OtrAndroidKeyManagerImpl.getInstance(activity);
        boolean overWriteExisting = true;
        boolean deleteImportedFile = true;
        oakm.importKeyStore(fileOtrKeyStore, password, overWriteExisting, deleteImportedFile);
        
    
    }
    
    public static boolean handleKeyScanResult (int requestCode, int resultCode, Intent data, Activity activity)
    {
        IntentResult scanResult =
                IntentIntegrator.parseActivityResult(requestCode, resultCode, data); 
        
        if  (scanResult != null) 
        { 
            
            String otrKeyPassword = scanResult.getContents();
            
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity.getApplicationContext());

            String otrKeyStorePath = prefs.getString("keystoreimport", null);
            
            Log.d("OTR","got password: " + otrKeyPassword + " for path: " + otrKeyStorePath);
            
            if (otrKeyPassword != null && otrKeyStorePath != null)
            {
                
                otrKeyPassword = otrKeyPassword.replace("\n","").replace("\r", ""); //remove any padding, newlines, etc
                
                try
                {
                    File otrKeystoreAES = new File(otrKeyStorePath);
                    if (otrKeystoreAES.exists()) {
                        OtrAndroidKeyManagerImpl.importOtrKeyStoreWithPassword(otrKeystoreAES, otrKeyPassword, activity);
                        return true;
                    }
                }
                catch (IOException e)
                {
                    Toast.makeText(activity, "unable to open keystore for import", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
            else
            {
                Log.d("OTR","no key store path saved");
                return false;
            }
            
        } 
        
        return false;
    }

}
