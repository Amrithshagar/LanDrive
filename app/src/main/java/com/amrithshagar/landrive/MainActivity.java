package com.amrithshagar.landrive;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
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
    private static final int MESSAGE_NEW_FOLDER = 2; // Add to your existing message types
    private static final String FOLDER_SYNC_PREFIX = "FOLDER_SYNC:"; // Protocol prefix
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        currentFolderContainer = findViewById(R.id.current_folder_container);
        folderIcon = findViewById(R.id.folder_icon);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Initialize adapter with empty list
        fileAdapter = new FileAdapter(new ArrayList<>());
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
            if (!isConnectionReady()) {
                Toast.makeText(this, "Connection not ready", Toast.LENGTH_SHORT).show();
                return;
            }
            if (syncFolder == null || !syncFolder.exists()) {
                Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show();
                return;
            }

            if (isHost) {
                if (serverClass != null) {
                    serverClass.syncFolder(syncFolder.getName());
                } else {
                    Toast.makeText(this, "Server not ready", Toast.LENGTH_SHORT).show();
                }
            } else {
                if (clientClass != null) {
                    String syncMessage = FOLDER_SYNC_PREFIX + syncFolder.getName();
                    clientClass.write(syncMessage.getBytes());
                } else {
                    Toast.makeText(this, "Client not connected", Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestFile();
            }
        });
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
            final InetAddress groupOwnerAddress = wifiP2pInfo.groupOwnerAddress;
            if(wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner)
            {
                connectionStatus.setText("Host");
                isHost = true;
                serverClass = new ServerClass();
                serverClass.start();//Since this is a thread class we need to start
            }
            else if(wifiP2pInfo.groupFormed)
            {
                connectionStatus.setText("Client");
                isHost = false;
                clientClass = new ClientClass(groupOwnerAddress);
                clientClass.start();
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

        public void write(byte[] bytes) {
            if (outputStream != null) {
                try {
                    outputStream.write(bytes);
                    outputStream.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                    // Handle disconnection
                    isRunning = false;
                }
            } else {
                Log.e("ServerClass", "Attempted to write to null output stream");
            }
        }

        @Override
        public void run() {
            isRunning = true;
            try {
                serverSocket = new ServerSocket(8888);
                socket = serverSocket.accept();
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

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
                e.printStackTrace();
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

        public void syncFolder(String folderName) {
            if (isRunning && outputStream != null) {
                String syncMessage = FOLDER_SYNC_PREFIX + folderName;
                write(syncMessage.getBytes());
            } else {
                Log.e("ServerClass", "Cannot sync - connection not ready");
                handler.post(() -> {
                    Toast.makeText(MainActivity.this, "Connection not ready", Toast.LENGTH_SHORT).show();
                });
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

        public void write(byte[] bytes) {
            if (outputStream != null) {
                try {
                    outputStream.write(bytes);
                    outputStream.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                    isRunning = false;
                }
            } else {
                Log.e("ClientClass", "Attempted to write to null output stream");
            }
        }

        @Override
        public void run() {
            isRunning = true;
            try {
                socket.connect(new InetSocketAddress(hostAdd, 8888), 500);
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

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
                e.printStackTrace();
            } finally {
                closeConnection();
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
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent returnIntent){
        super.onActivityResult(requestCode, resultCode, returnIntent);
        Uri returnUri = returnIntent.getData();
        if (resultCode != RESULT_OK){
            return;
        } else {
            try {
                inputPFD = getContentResolver().openFileDescriptor(returnUri,"r");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                Log.e("FileSelectActivity","File Not Found");
                return;
            }
            if (inputPFD != null) {
                FileDescriptor fd = inputPFD.getFileDescriptor();
            }
        }
        TextView fileNameView = findViewById(R.id.file_name);
        Cursor cursor = getContentResolver().query(returnUri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            @SuppressLint("Range") String displayName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
            fileNameView.setText(displayName);
            cursor.close();
        }


    }
    public void createSyncFolder(String folderName) {
        folderName = folderName.replaceAll("[\\\\/:*?\"<>|]", "");
        syncFolder = new File(getExternalFilesDir(null), folderName);

        if (syncFolder.exists()) {
            Toast.makeText(this, "Folder '" + folderName + "' already exists", Toast.LENGTH_SHORT).show();
        } else {
            boolean created = syncFolder.mkdirs();
            if (created) {
                updateFolderUI(folderName);
                Toast.makeText(this, "Folder '" + folderName + "' created", Toast.LENGTH_SHORT).show();

                // If client creates folder and wants to sync back to host
                if (!isHost && clientClass != null) {
                    clientClass.write((FOLDER_SYNC_PREFIX + folderName).getBytes());
                }
            } else {
                Toast.makeText(this, "Failed to create folder", Toast.LENGTH_SHORT).show();
            }
        }
        loadFiles();
    }

    private void loadFiles() {
        if (syncFolder == null || !syncFolder.exists()) {
            // Show empty state or default message
            fileAdapter.updateFiles(new ArrayList<>());
            TextView folderNameView = findViewById(R.id.folder_name);
            if (folderNameView != null) {
                folderNameView.setText("No folder selected");
            }
            return;
        }

        File[] files = syncFolder.listFiles();
        List<FileAdapter.FileItem> fileItems = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                FileAdapter.FileItem item = new FileAdapter.FileItem(
                        file.getName(),
                        file.getAbsolutePath()
                );
                item.setFileSize(file.length());
                fileItems.add(item);
            }
        }

        fileAdapter.updateFiles(fileItems);

        // Update folder name display
        TextView folderNameView = findViewById(R.id.folder_name);
        if (folderNameView != null) {
            folderNameView.setText("Current Folder: " + syncFolder.getName());
        }
    }
    private void updateFolderUI(String folderName) {
        TextView folderNameView = findViewById(R.id.folder_name);
        if (folderNameView != null) {
            folderNameView.setText(folderName);
        }

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

