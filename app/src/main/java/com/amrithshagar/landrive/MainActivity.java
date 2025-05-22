package com.amrithshagar.landrive;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    Button btnOnOff, btnDiscover, btnSend, btnShare, btnNewFolder, btnSync;
    ListView listView;
    TextView readMsgBox, connectionStatus;
    EditText writeMsg;
    WifiManager wifiManager;
    WifiP2pManager p2pManager;
    WifiP2pManager.Channel channel;

    BroadcastReceiver wifiScanReceiver;
    IntentFilter intentFilter;
    List<ScanResult> results;

    List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    String[] deviceNameArray;
    WifiP2pDevice[] deviceArray;
    private static final String FILE_SYNC_PREFIX = "FILE_SYNC:";
    private static final String FILE_DATA_PREFIX = "FILE_DATA:";
    private static final int BUFFER_SIZE = 8192; // 8KB buffer
    static final int MESSAGE_READ = 1;
    private ParcelFileDescriptor inputPFD;
    private LinearLayout currentFolderContainer;
    private ImageView folderIcon;
    Socket socket;

    ServerClass serverClass;
    ClientClass clientClass;
    boolean isHost;
    //    SendReceive sendReceive;
    private RecyclerView recyclerView;
    private FileAdapter fileAdapter;
    private File syncFolder;
    private final Object connectionLock = new Object();
    private boolean isSocketReady = false;
    // Add these constants at the top of MainActivity
    private static final String FOLDER_SYNC_PREFIX = "FOLDER_SYNC:";
    private static final int SYNC_TIMEOUT_MS = 10000; // 10 seconds
    private static final int MESSAGE_NEW_FOLDER = 2; // Add to your existing message types
    private static final int FILE_SELECT_CODE = 1001;
    private File currentDirectory;
    private final Stack<File> directoryStack = new Stack<>();
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        currentFolderContainer = findViewById(R.id.current_folder_container);
        folderIcon = findViewById(R.id.folder_icon);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Initialize adapter with empty list
        fileAdapter = new FileAdapter(new ArrayList<>(), fileItem -> {
            File clickedFile = new File(fileItem.getFilePath());
            if (fileItem.isDirectory()) {
                // Navigate into folder
                directoryStack.push(currentDirectory);
                currentDirectory = clickedFile;
                loadFiles();
            } else {
                // Handle file click (preview or open)
                openFile(clickedFile);
            }
        });
        recyclerView.setAdapter(fileAdapter);

        initialWork();
        ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
        }, 0);
        exqListener();

        // Initialize sync folder
        createSyncFolder("default_folder_name"); // Provide a default folder name
        currentFolderContainer.setOnClickListener(v -> {
            if (syncFolder != null && syncFolder.exists()) {
                loadFiles(); // Refresh the view
                Toast.makeText(this, "Viewing folder: " + syncFolder.getName(), Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void openFile(File file) {
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".provider", file);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, getMimeType(file.getPath()));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private String getMimeType(String path) {
        String extension = path.substring(path.lastIndexOf(".") + 1);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0) {
            for(int i = 0; i < permissions.length; i++){
                if(grantResults[i] == PackageManager.PERMISSION_GRANTED){
                    Log.d("MainActivity", "Permission " + permissions[i] + " granted");
                }else{
                    Log.d("MainActivity", "Permission " + permissions[i] + " denied");
                }
            }
        }

    }

    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            if (msg.what == MESSAGE_READ) {
                byte[] readBuff = (byte[]) msg.obj;
                String tempMsg = new String(readBuff, 0, msg.arg1);
                readMsgBox.setText(tempMsg);
            }
            return true;
        }
    });
    private void setupRecyclerView() {
        fileAdapter = new FileAdapter(new ArrayList<>(), fileItem -> {
            File clickedFile = new File(fileItem.getFilePath());
            if (clickedFile.isDirectory()) {
                // Navigate into folder
                directoryStack.push(currentDirectory);
                currentDirectory = clickedFile;
                loadFiles();
            } else {
                // Handle file click
                Toast.makeText(this, "Selected file: " + fileItem.getFileName(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        recyclerView.setAdapter(fileAdapter);
    }

    public void navigateUp() {
        if (!directoryStack.isEmpty()) {
            currentDirectory = directoryStack.pop();
            loadFiles();
        }
    }
    private void exqListener(){
        btnOnOff.setOnClickListener(new View.OnClickListener() {
            final ActivityResultLauncher<Intent> wifiSettingsLauncher =
                    registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                            result -> {
                                if (result.getResultCode() == Activity.RESULT_OK) {
                                    // Handle result if needed
                                }
                            });

            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                wifiSettingsLauncher.launch(intent);
            }
        });
        // Add the new folder button listener here
        btnNewFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFolderNameDialog();
            }
        });
        btnDiscover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        connectionStatus.setText("Discovery started");
                    }

                    @Override
                    public void onFailure(int reasonCode) {
                        connectionStatus.setText("Discovery failed: " + reasonCode);
                        Log.d("WIFI_P2P", "Discovery failed with code " + reasonCode);
                    }
                });
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final WifiP2pDevice device = deviceArray[position];
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = device.deviceAddress;

                p2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(getApplicationContext(),"Connected to"+device.deviceName +" From "+device.deviceAddress, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onFailure(int i) {
                        Toast.makeText(getApplicationContext(),"Not Connected", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExecutorService executor = Executors.newSingleThreadExecutor();
                String msg = writeMsg.getText().toString();
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if(msg!=null && isHost)
                        {
                            serverClass.write(msg.getBytes());
                        } else if (msg!= null && !isHost)
                        {
                            clientClass.write(msg.getBytes());
                        }
                    }
                });
//                sendReceive.write(msg.getBytes());
            }
        });
        btnSync.setOnClickListener(v -> {
            btnSync.setEnabled(false);
            btnSync.setText("Syncing...");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                try {
                    synchronized (connectionLock) {
                        long startTime = System.currentTimeMillis();
                        while (!isSocketReady &&
                                (System.currentTimeMillis() - startTime) < 10000) {
                            connectionLock.wait(500);
                        }
                    }

                    if (!isSocketReady) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Connection not ready",
                                    Toast.LENGTH_LONG).show();
                        });
                        return;
                    }

                    if (syncFolder == null || !syncFolder.exists()) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "No folder selected",
                                    Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    if (!isHost) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Only host can initiate sync",
                                    Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    // Sync entire folder contents
                    serverClass.syncFolderContents(syncFolder);

                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "Folder sync completed",
                                Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "Sync failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                } finally {
                    runOnUiThread(() -> {
                        btnSync.setEnabled(true);
                        btnSync.setText("Sync");
                    });
                }
            });
        });
        btnShare.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, FILE_SELECT_CODE);
        });

    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_SELECT_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                addFileToFolder(uri);
            }
        }
    }
    public boolean isConnectionReady() {
        if (isHost) {
            return serverClass != null && serverClass.isRunning;
        } else {
            return clientClass != null && clientClass.isRunning;
        }
    }

    private void initialWork() {
        btnOnOff = (Button) findViewById(R.id.onOff);
        btnDiscover = (Button) findViewById(R.id.discover);
        btnSend = (Button) findViewById(R.id.sendButton);
        btnShare = findViewById(R.id.share);
        btnNewFolder = findViewById(R.id.newFolder); // Initialize here
        btnSync = findViewById(R.id.btnSync);

        listView = (ListView) findViewById(R.id.peerListView);
        readMsgBox = (TextView) findViewById(R.id.readMsg);
        connectionStatus = (TextView) findViewById(R.id.connectionStatus);
        writeMsg = (EditText) findViewById(R.id.writeMsg);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        p2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = p2pManager.initialize(this,getMainLooper(),null);

        wifiScanReceiver = new WiFiDirectBroadcastReceiver(p2pManager,channel,this);
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);


    }
    WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {
            if(!peerList.getDeviceList().equals(peers))
            {
                peers.clear();
                peers.addAll(peerList.getDeviceList());

                deviceNameArray = new String[peerList.getDeviceList().size()];
                deviceArray = new WifiP2pDevice[peerList.getDeviceList().size()];
                int index = 0;

                for(WifiP2pDevice device: peerList.getDeviceList())
                {
                    deviceNameArray[index] = device.deviceName;
                    deviceArray[index] = device;
                    index++;
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(getApplicationContext(),
                        android.R.layout.simple_list_item_1,deviceNameArray);
                listView.setAdapter(adapter);
            }
            if(peers.size()==0){
                Toast.makeText(getApplicationContext(),"No Device Found",Toast.LENGTH_SHORT).show();
                return;
            }
        }
    };
    WifiP2pManager.ConnectionInfoListener connectionInfoListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            synchronized (connectionLock) {
                Log.d("ConnectionInfo", "Group Owner IP: " +
                        (wifiP2pInfo.groupOwnerAddress != null ?
                                wifiP2pInfo.groupOwnerAddress.getHostAddress() : "null"));

                if(wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
                    connectionStatus.setText("Initializing Host...");
                    isHost = true;
                    serverClass = new ServerClass();
                    serverClass.start();
                }
                else if(wifiP2pInfo.groupFormed) {
                    connectionStatus.setText("Initializing Client...");
                    isHost = false;
                    clientClass = new ClientClass(wifiP2pInfo.groupOwnerAddress);
                    clientClass.start();
                }
                connectionLock.notifyAll();
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(wifiScanReceiver,intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(wifiScanReceiver);
    }

    public class ServerClass extends Thread {
        ServerSocket serverSocket;
        private InputStream inputStream;
        private OutputStream outputStream;
        private boolean isRunning = false;

        private final Object socketLock = new Object(); // Add this line

        // Updated write methods
        public void write(byte[] buffer, int offset, int length) {
            synchronized (socketLock) {
                if (outputStream != null) {
                    try {
                        outputStream.write(buffer, offset, length);
                        outputStream.flush();
                    } catch (IOException e) {
                        Log.e("ServerClass", "Write failed", e);
                        isRunning = false;
                    }
                }
            }
        }

        public void write(byte[] bytes) {
            write(bytes, 0, bytes.length);
        }


        @Override
        public void run() {
            isRunning = true;
            try {
                serverSocket = new ServerSocket(8888);
                Log.d("ServerClass", "Server socket created on port 8888");

                handler.post(() -> connectionStatus.setText("Host - Waiting for client..."));

                socket = serverSocket.accept();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                synchronized (connectionLock) {
                    isSocketReady = true;
                    connectionLock.notifyAll();
                }

                handler.post(() -> {
                    connectionStatus.setText("Host - Connected");
                    Toast.makeText(MainActivity.this,
                            "Client connected", Toast.LENGTH_SHORT).show();
                });

                byte[] buffer = new byte[1024];
                int bytes;
                while (isRunning && socket != null) {
                    try {
                        bytes = inputStream.read(buffer);
                        if (bytes > 0) {
                            String receivedMessage = new String(buffer, 0, bytes);
                            handler.post(() -> {
                                if (receivedMessage.startsWith(FOLDER_SYNC_PREFIX)) {
                                    String folderName = receivedMessage.substring(FOLDER_SYNC_PREFIX.length());
                                    createSyncFolder(folderName);
                                } else {
                                    readMsgBox.setText(receivedMessage);
                                }
                            });
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        isRunning = false;
                    }
                }
            } catch (IOException e) {
                Log.e("ServerClass", "Server error: " + e.getMessage());
                handler.post(() -> {
                    connectionStatus.setText("Host - Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this,
                            "Server error", Toast.LENGTH_LONG).show();
                });
            } finally {
                closeConnection();
            }
        }

        private void closeConnection() {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                if (socket != null) socket.close();
                if (serverSocket != null) serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // In your ServerClass
// In ServerClass
//        public void syncFolder(String folderName) {
//            ExecutorService executor = Executors.newSingleThreadExecutor();
//            executor.execute(() -> {
//                if (isRunning && outputStream != null) {
//                    try {
//                        String syncMessage = FOLDER_SYNC_PREFIX + folderName;
//                        outputStream.write(syncMessage.getBytes(StandardCharsets.UTF_8));
//                        outputStream.flush();
//                        Log.d("Sync", "Folder sync initiated: " + folderName);
//
//                        handler.post(() ->
//                                Toast.makeText(MainActivity.this,
//                                        "Folder sync initiated",
//                                        Toast.LENGTH_SHORT).show());
//
//                    } catch (IOException e) {
//                        Log.e("Sync", "Failed to sync folder", e);
//                        handler.post(() ->
//                                Toast.makeText(MainActivity.this,
//                                        "Sync failed: " + e.getMessage(),
//                                        Toast.LENGTH_LONG).show());
//                    }
//                }
//            });
//        }
        // In ServerClass
        public void syncFolderContents(File folder) {
            if (!isRunning || outputStream == null) return;

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                try {
                    // First sync the folder structure
                    String syncMessage = FOLDER_SYNC_PREFIX + folder.getName();
                    write(syncMessage.getBytes(StandardCharsets.UTF_8));

                    // Then sync all files in the folder
                    File[] files = folder.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile()) {
                                syncFile(file);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("Sync", "Folder sync failed", e);
                }
            });
        }

        private void syncFile(File file) {
            try (FileInputStream fis = new FileInputStream(file)) {
                // 1. Send file header with metadata
                String header = "FILE_START:" + file.getName() + ":" + file.length();
                write(header.getBytes(StandardCharsets.UTF_8));

                // 2. Send file content in chunks
                byte[] buffer = new byte[8192]; // 8KB buffer
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    write(buffer, 0, bytesRead);
                }

                // 3. Send end marker
                write("FILE_END".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.e("Sync", "File sync failed", e);
            }
        }
    }

    public class ClientClass extends Thread {
        String hostAdd;
        private InputStream inputStream;
        private OutputStream outputStream;
        private boolean isRunning = false;

        public ClientClass(InetAddress hostAddress) {
            hostAdd = hostAddress.getHostAddress();
            socket = new Socket();
        }

        private final Object socketLock = new Object(); // Add this line

        // Updated write methods
        public void write(byte[] buffer, int offset, int length) {
            synchronized (socketLock) {
                if (outputStream != null) {
                    try {
                        outputStream.write(buffer, offset, length);
                        outputStream.flush();
                    } catch (IOException e) {
                        Log.e("ClientClass", "Write failed", e);
                        isRunning = false;
                    }
                }
            }
        }

        public void write(byte[] bytes) {
            write(bytes, 0, bytes.length);
        }


        @Override
        public void run() {
            isRunning = true;
            int retryCount = 0;
            final int maxRetries = 3;
            while (retryCount < maxRetries && isRunning) {
                try {
                    Log.d("ClientClass", "Attempting to connect to " + hostAdd + ":8888");
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(hostAdd, 8888), 10000); // 10 second timeout
                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    synchronized (connectionLock) {
                        isSocketReady = true;
                        connectionLock.notifyAll();
                    }

                    handler.post(() -> {
                        connectionStatus.setText("Client - Connected");
                        Toast.makeText(MainActivity.this,
                                "Connected to host", Toast.LENGTH_SHORT).show();
                    });
                    byte[] buffer = new byte[1024];
                    int bytes;
                    while (isRunning && socket != null) {
                        try {
                            bytes = inputStream.read(buffer);
                            if (bytes > 0) {
                                final String receivedMessage = new String(buffer, 0, bytes);
                                final int finalBytes = bytes;

                                // Handle message in background thread
                                ExecutorService executor = Executors.newSingleThreadExecutor();
                                executor.execute(() -> {
                                    if (receivedMessage.startsWith(FOLDER_SYNC_PREFIX)) {
                                        String folderName = receivedMessage.substring(FOLDER_SYNC_PREFIX.length());

                                        // Run folder creation on main thread since it updates UI
                                        handler.post(() -> {
                                            createSyncFolder(folderName);
                                            Toast.makeText(MainActivity.this,
                                                    "Created folder: " + folderName,
                                                    Toast.LENGTH_SHORT).show();
                                        });

                                        // Send confirmation in background
                                        try {
                                            String confirmation = "SYNC_OK:" + folderName;
                                            outputStream.write(confirmation.getBytes(StandardCharsets.UTF_8));
                                            outputStream.flush();
                                        } catch (IOException e) {
                                            Log.e("ClientClass", "Confirmation failed", e);
                                        }
                                    } else if(receivedMessage.startsWith("FILE_START:")){
                                        handleFileTransfer(inputStream, receivedMessage);
                                    }
                                    else {
                                        // Regular messages can be handled on main thread
                                        handler.post(() -> readMsgBox.setText(receivedMessage));
                                    }
                                });
                            }

                        } catch (IOException e) {
                            Log.e("ClientClass", "Connection failed", e);
                            handler.post(() -> {
                                connectionStatus.setText("Connection failed: " + e.getMessage());
                                Toast.makeText(MainActivity.this,
                                        "Connection failed", Toast.LENGTH_LONG).show();
                            });
                            isRunning = false;
                        }
                    }
                    break;
                } catch (IOException e) {
                    retryCount++;
                    Log.e("ClientClass", "Connection attempt " + retryCount + " failed: " + e.getMessage());

                    if (retryCount < maxRetries) {
                        try {
                            Thread.sleep(2000); // Wait 2 seconds before retry
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        handler.post(() -> {
                            connectionStatus.setText("Connection failed");
                            Toast.makeText(MainActivity.this,
                                    "Failed to connect after " + maxRetries + " attempts",
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                } finally {
                    closeConnection();
                }

            }

        }

        private void closeConnection() {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        private void handleFileTransfer(InputStream inputStream, String initialMessage) {
            try {
                // Parse the initial message which contains file metadata
                String[] parts = initialMessage.split(":");
                String fileName = parts[1];
                long fileSize = Long.parseLong(parts[2]);

                // Create the file in sync folder
                File receivedFile = new File(syncFolder, fileName);
                try (FileOutputStream fos = new FileOutputStream(receivedFile)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long bytesReceived = 0;
                    int bytesRead;

                    // Read the file content
                    while (bytesReceived < fileSize &&
                            (bytesRead = inputStream.read(buffer, 0, (int) Math.min(buffer.length, fileSize - bytesReceived))) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        bytesReceived += bytesRead;
                    }

                    // Verify we received the complete file
                    if (bytesReceived != fileSize) {
                        throw new IOException("File transfer incomplete. Expected: " + fileSize + ", received: " + bytesReceived);
                    }
//
//                    // Read the FILE_END marker
//                    byte[] endMarker = new byte[8];
//                    inputStream.read(endMarker);
//                    if (!"FILE_END".equals(new String(endMarker))) {
//                        throw new IOException("Invalid file end marker");
//                    }

                    runOnUiThread(() -> {
                        openReceivedFile(receivedFile); // Open the received file
                        loadFiles(); // Refresh file list
                        Toast.makeText(MainActivity.this,
                                "Received file: " + fileName,
                                Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                Log.e("FileTransfer", "Error receiving file", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "File transfer failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }

        // Helper method to read a line from input stream
        private String readLine(InputStream is) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = is.read()) != -1 && b != '\n') {
                baos.write(b);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    public void onReceive(Context context, Intent intent){
        boolean success = intent.getBooleanExtra(
                WifiManager.EXTRA_RESULTS_UPDATED, false);
        if (success) {
            scanSuccess();
        } else {
            // scan failure handling
            scanFailure();
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        context.registerReceiver(wifiScanReceiver, intentFilter);

        success = wifiManager.startScan();
        if (!success) {
            // scan failure handling
            scanFailure();
        }
    }

    private void scanSuccess() {
        @SuppressLint("MissingPermission") List<ScanResult> results = wifiManager.getScanResults();
    }

    private void scanFailure() {
        // handle failure: new scan did NOT succeed
        // consider using old scan results: these are the OLD results!
        @SuppressLint("MissingPermission") List<ScanResult> results = wifiManager.getScanResults();
    }


    // Add this method to handle file additions
    private void addFileToFolder(Uri fileUri) {
        if (syncFolder == null || !syncFolder.exists()) {
            Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show();
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                String fileName = getFileName(fileUri);
                File destFile = new File(syncFolder, fileName);

                try (InputStream in = getContentResolver().openInputStream(fileUri);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                runOnUiThread(() -> {
                    loadFiles(); // Refresh view
                    Toast.makeText(MainActivity.this,
                            "Added file: " + fileName,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Failed to add file: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @SuppressLint("Range")
    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    protected void requestFile(){
        Intent requestFileIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        requestFileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        requestFileIntent.setType("*/*"); // all file types
        startActivityForResult(Intent.createChooser(requestFileIntent, "Select File"), 0);

    }
    private void showFolderNameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create New Folder");

        // Set up the input
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Enter folder name");
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Create", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String folderName = input.getText().toString().trim();
                if (!folderName.isEmpty()) {
                    createSyncFolder(folderName);
                } else {
                    Toast.makeText(MainActivity.this, "Folder name cannot be empty", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    public void createSyncFolder(String folderName) {
        // Sanitize folder name
        folderName.replaceAll("[\\\\/:*?\"<>|]", "");

        // Create folder in app-specific storage
        syncFolder = new File(getExternalFilesDir(null), folderName);

        if (syncFolder.exists()) {
            runOnUiThread(() ->
                    Toast.makeText(this,
                            "Folder already exists: " + folderName,
                            Toast.LENGTH_SHORT).show());
        } else {
            boolean created = syncFolder.mkdirs();
            runOnUiThread(() -> {
                if (created) {
                    updateFolderUI(folderName);
                    Toast.makeText(this,
                            "Folder created: " + folderName,
                            Toast.LENGTH_SHORT).show();
                    loadFiles(); // Refresh file list
                } else {
                    Toast.makeText(this,
                            "Failed to create folder",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void loadFiles() {
        if (currentDirectory == null) {
            currentDirectory = syncFolder; // Default to sync folder
        }

        File[] files = currentDirectory.listFiles();
        List<FileAdapter.FileItem> fileItems = new ArrayList<>();

        if (files != null) {
            for (File file : files) {
                FileAdapter.FileItem item = new FileAdapter.FileItem(
                        file.getName(),
                        file.getAbsolutePath()
                );
                item.setFileSize(file.length());
                item.setDirectory(file.isDirectory());
                fileItems.add(item);
            }
        }

        fileAdapter.updateFiles(fileItems);

        // Update folder path display
        updateFolderUI(currentDirectory.getName());
    }
    private void openReceivedFile(File file) {
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".provider", file);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, getMimeType(file.getName()));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "No app available to open this file",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void updateFolderUI(String folderName) {
        TextView folderNameView = findViewById(R.id.folder_name);
        folderNameView.setText(folderName);

        // Make the folder container visible if it was hidden
        currentFolderContainer.setVisibility(View.VISIBLE);

        // You can add animation here if desired
        currentFolderContainer.animate()
                .scaleX(1.05f)
                .scaleY(1.05f)
                .setDuration(200)
                .withEndAction(() -> currentFolderContainer.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200))
                .start();
    }

    //to handle keyboard error
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        }
    }

}

