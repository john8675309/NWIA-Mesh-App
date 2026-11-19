package com.nwiamesh.app;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import java.util.*;
import java.util.function.Consumer;
import com.nwiamesh.core.ModemProtocol;

/** Nordic UART byte stream. Stock companions are rejected by the NWA1 keyless handshake. */
@SuppressWarnings({"MissingPermission","deprecation"})
public final class BleTransport implements AutoCloseable {
    static final UUID SERVICE=UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID WRITE=UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID NOTIFY=UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public interface Listener {void status(String status);void receive(byte[] raw);void disconnected();}
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());private final Listener listener;
    private final BluetoothAdapter adapter;private BluetoothGatt gatt;private BluetoothGattCharacteristic write;
    private boolean ready,writing,retryAllowed;private int connectionGeneration;private long nextId=1;
    private final ArrayDeque<byte[]> chunks=new ArrayDeque<>();
    private final Map<Long,Consumer<Boolean>> awaiting=new HashMap<>();
    private ModemProtocol.Decoder decoder=new ModemProtocol.Decoder();private ScanCallback scan;private Consumer<List<BluetoothDevice>> scanDone;
    public BleTransport(Context context,Listener listener) {this.context=context;this.listener=listener;BluetoothManager manager=context.getSystemService(BluetoothManager.class);adapter=manager==null?null:manager.getAdapter();}
    public boolean ready(){return ready;}
    public void scan(Consumer<List<BluetoothDevice>> done) {
        if(adapter==null || !adapter.isEnabled()){listener.status("Enable Bluetooth first");done.accept(List.of());return;}
        if(scan!=null){listener.status("Scan already running");done.accept(List.of());return;}
        Map<String,BluetoothDevice> found=new LinkedHashMap<>();
        for(BluetoothDevice bonded:adapter.getBondedDevices()){String name=bonded.getName();if(name!=null && name.startsWith("NWIA Keyless"))found.put(bonded.getAddress(),bonded);}
        scanDone=done;
        scan=new ScanCallback(){
            public void onScanResult(int type,ScanResult result){found.put(result.getDevice().getAddress(),result.getDevice());}
            public void onScanFailed(int error){ScanCallback failed=this;main.post(()->{if(scan==failed){finishScan(new ArrayList<>(found.values()));listener.status("Bluetooth scan failed");}});}
        };
        ScanCallback current=scan;
        try{adapter.getBluetoothLeScanner().startScan(List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scan);}catch(RuntimeException error){finishScan(new ArrayList<>(found.values()));listener.status("Bluetooth scan failed");return;}
        listener.status("Scanning for companions…");
        main.postDelayed(()->{if(scan==current){finishScan(new ArrayList<>(found.values()));}},8000);
    }
    private void stopScan(){try{if(scan!=null && adapter!=null && adapter.getBluetoothLeScanner()!=null)adapter.getBluetoothLeScanner().stopScan(scan);}catch(RuntimeException ignored){}finally{scan=null;}}
    private void finishScan(List<BluetoothDevice> devices){var callback=scanDone;scanDone=null;stopScan();if(callback!=null)callback.accept(devices);}
    public void connect(BluetoothDevice device){connect(device,true);}
    private void connect(BluetoothDevice device,boolean retry){
        close();final int generation=connectionGeneration;retryAllowed=retry;decoder=new ModemProtocol.Decoder();listener.status("Connecting to companion…");
        // Give Android time to finish stopScan/old GATT teardown before a new connect.
        main.postDelayed(()->{if(generation!=connectionGeneration)return;gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);BluetoothGatt attempt=gatt;main.postDelayed(()->{if(generation==connectionGeneration && gatt==attempt && !ready){listener.status("Companion handshake timed out");close();}},120000);},600);
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        public void onConnectionStateChange(BluetoothGatt source,int status,int state){main.post(()->{
            if(source!=gatt)return;
            if(status==133 && !ready && retryAllowed){connect(source.getDevice(),false);listener.status("Retrying companion connection…");return;}
            if(status!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED){listener.status("Companion disconnected (GATT "+status+")");close();}
            else if(state==BluetoothProfile.STATE_CONNECTED){listener.status("Companion connected; checking services…");if(!source.discoverServices())close();}
        });}
        public void onServicesDiscovered(BluetoothGatt source,int status){main.post(()->{
            if(source!=gatt)return;
            BluetoothGattService service=source.getService(SERVICE);
            if(status!=BluetoothGatt.GATT_SUCCESS || service==null){listener.status("Unsupported companion");close();return;}
            listener.status("Setting up encrypted companion link…");write=service.getCharacteristic(WRITE);BluetoothGattCharacteristic notify=service.getCharacteristic(NOTIFY);
            if(write==null || notify==null || !source.setCharacteristicNotification(notify,true)){close();return;}
            BluetoothGattDescriptor descriptor=notify.getDescriptor(CCCD);if(descriptor==null){close();return;}
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if(!source.writeDescriptor(descriptor))close();
        });}
        public void onDescriptorWrite(BluetoothGatt source,BluetoothGattDescriptor descriptor,int status){main.post(()->{
            if(source!=gatt)return;if(status!=BluetoothGatt.GATT_SUCCESS){listener.status("Bluetooth pairing/notification setup failed");close();return;}
            listener.status("Checking keyless modem protocol…");enqueue(ModemProtocol.encode(new ModemProtocol.Frame(ModemProtocol.HELLO,0,new byte[]{'N','W','A','1'})));
        });}
        public void onCharacteristicWrite(BluetoothGatt source,BluetoothGattCharacteristic characteristic,int status){main.post(()->{
            if(source!=gatt)return;if(status!=BluetoothGatt.GATT_SUCCESS){listener.status("Companion write failed");close();return;}
            writing=false;pump();
        });}
        public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic characteristic,byte[] value){byte[] copy=value.clone();main.post(()->accept(source,copy));}
        public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic characteristic){onCharacteristicChanged(source,characteristic,characteristic.getValue());}
    };
    private void accept(BluetoothGatt source,byte[] value) {
        if(source!=gatt)return;
        try {for(var f:decoder.feed(value)) {
            if(ModemProtocol.isKeylessCaps(f) && f.requestId()==0){ready=true;listener.status("Keyless companion ready");}
            else if(ready && f.opcode()==ModemProtocol.TX_RESULT && f.data().length==1){var done=awaiting.remove(f.requestId());if(done!=null)done.accept(f.data()[0]==1);}
            else if(ready && f.opcode()==ModemProtocol.RECEIVE)listener.receive(f.data());
        }}catch(IllegalArgumentException|IllegalStateException error){listener.status("Invalid companion protocol");close();}
    }
    public void send(byte[] raw,Consumer<Boolean> done) {
        if(!ready || awaiting.size()>=8){done.accept(false);return;}
        long id=nextId++;awaiting.put(id,done);enqueue(ModemProtocol.encode(new ModemProtocol.Frame(ModemProtocol.TRANSMIT,id,raw)));
        main.postDelayed(()->{var callback=awaiting.remove(id);if(callback!=null){callback.accept(false);close();}},10000);
    }
    private void enqueue(byte[] bytes){for(int i=0;i<bytes.length;i+=20)chunks.add(Arrays.copyOfRange(bytes,i,Math.min(i+20,bytes.length)));pump();}
    private void pump(){if(writing || chunks.isEmpty() || gatt==null || write==null)return;write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);write.setValue(chunks.remove());writing=true;if(!gatt.writeCharacteristic(write))close();}
    public void close(){++connectionGeneration;finishScan(List.of());ready=false;writing=false;chunks.clear();write=null;var current=gatt;gatt=null;if(current!=null){current.disconnect();current.close();}var callbacks=new ArrayList<>(awaiting.values());awaiting.clear();for(var done:callbacks)done.accept(false);listener.disconnected();}
}
