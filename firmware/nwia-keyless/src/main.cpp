// NWIA keyless BLE modem. No mesh identity, channel key, shared secret, or filesystem.
#include <Arduino.h>
#include <bluefruit.h>
#include <target.h>
#include <BridgeCore.h>
#include <Adafruit_SH110X.h>

static BLEUart uart;
static Adafruit_SH1106G oled(128,64,&Wire,-1);
static char usbLine[16];
static uint8_t usbLength=0;
static uint8_t input[260], inputHigh=0;
static uint16_t expected=0, received=0;
static bool firstLength=true, haveHigh=false, hello=false;
struct Output { uint16_t len, offset; uint8_t bytes[262]; };
static Output output[4];
static uint8_t head=0, count=0;
static bool pending=false, transmitting=false, cooling=false;
static uint8_t tx[255];
static uint16_t txLen=0;
static uint32_t txId=0, txStarted=0, cooldown=0;
static volatile uint16_t requestedConnection=BLE_CONN_HANDLE_INVALID;
static volatile uint32_t linkGeneration=0;
static uint32_t processedGeneration=0;
static uint16_t activeConnection=BLE_CONN_HANDLE_INVALID;
static uint32_t pinCode=0, session=0, txSession=0;
static uint32_t readId(const uint8_t* b) {return uint32_t(b[0])<<24|uint32_t(b[1])<<16|uint32_t(b[2])<<8|b[3];}
static bool queue(uint8_t opcode,uint32_t id,const uint8_t* data,uint16_t len) {
  if(count==4 || len>255) return false;
  auto& frame=output[(head+count)%4]; frame.len=len+7;frame.offset=0;
  frame.bytes[0]=(len+5)>>8;frame.bytes[1]=len+5;frame.bytes[2]=opcode;
  for(unsigned i=0;i<4;i++)frame.bytes[3+i]=id>>(24-8*i);
  if(len)memcpy(frame.bytes+7,data,len);count++;return true;
}
static void result(uint32_t id,uint8_t status) {queue(0x82,id,&status,1);}
static void resetLink() {
  ++session;expected=received=0;firstLength=true;haveHigh=false;hello=false;head=count=0;
  // A transmission already started cannot be recalled. Never attribute it to a new BLE session.
  if(!transmitting)pending=false;
}
static void disconnected(uint16_t connection,uint8_t) {if(connection==requestedConnection){requestedConnection=BLE_CONN_HANDLE_INVALID;++linkGeneration;}}
static void connected(uint16_t connection) {requestedConnection=connection;++linkGeneration;}
static bool secured() {
  auto c=Bluefruit.Connection(activeConnection);
  return c && c->connected() && c->secured();
}
static void command() {
  const uint8_t op=input[0];const uint32_t id=readId(input+1);const uint16_t len=expected-5;
  if(op==1 && len==4 && !memcmp(input+5,"NWA1",4)) {
    const uint8_t caps[]={'N','W','A','1',1};hello=true;queue(0x81,id,caps,sizeof(caps));
  } else if(op==2 && hello) {
    if(pending || transmitting || count>=3 || cooling) {result(id,2);return;}
    if(!nwia::validRaw(input+5,len,nwia::Mode::All)) {result(id,0);return;}
    memcpy(tx,input+5,len);txLen=len;txId=id;txSession=session;pending=true;txStarted=millis();
  }
}
void setup() {
  board.begin();Serial.begin(115200);
  if(!radio_init())while(true)delay(1000);
  radio_driver.begin();radio_driver.setParams(910.525,62.5,7,5);radio_driver.setTxPower(22);
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);Bluefruit.begin();Bluefruit.setName("NWIA Keyless L1");
  Bluefruit.Periph.setConnectCallback(connected);Bluefruit.Periph.setDisconnectCallback(disconnected);
  // Per-boot pairing PIN is shown only over local USB; never a message key.
  // Production UI must display this PIN and gate new pairing on a physical button.
  pinCode=100000+(uint32_t(radio_driver.getRngSeed())%900000);
  char pin[7];snprintf(pin,sizeof(pin),"%06lu",(unsigned long)pinCode);
  Bluefruit.Security.setIOCaps(true,false,false);Bluefruit.Security.setPIN(pin);
  Serial.print("NWIA BLE pairing PIN: ");Serial.println(pin);
  uint8_t oledAddress=DISPLAY_ADDRESS;Wire.beginTransmission(oledAddress);if(Wire.endTransmission()!=0)oledAddress^=1;
  if(oled.begin(oledAddress,true)) {oled.clearDisplay();oled.setTextColor(SH110X_WHITE);oled.setTextSize(1);oled.setCursor(0,0);oled.print("NWIA KEYLESS L1");oled.setTextSize(2);oled.setCursor(0,22);oled.print(pin);oled.setTextSize(1);oled.setCursor(0,52);oled.print("Keys stay on phone");oled.display();}
  uart.setPermission(SECMODE_ENC_WITH_MITM,SECMODE_ENC_WITH_MITM);uart.begin();
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();Bluefruit.Advertising.addService(uart);
  Bluefruit.ScanResponse.addName();Bluefruit.Advertising.restartOnDisconnect(true);Bluefruit.Advertising.start(0);
  board.onBootComplete();
}
void loop() {
  while(Serial.available()) {char c=Serial.read();if(c=='\r' || c=='\n'){usbLine[usbLength]=0;if(!strcmp(usbLine,"pin")){Serial.print("NWIA BLE pairing PIN: ");Serial.println(pinCode);}else if(!strcmp(usbLine,"status")){Serial.print("NWIA keyless v1; message keys=none; BLE=");Serial.println(secured()?"secured":"disconnected");}usbLength=0;}else if(usbLength<sizeof(usbLine)-1 && c>=32 && c<127)usbLine[usbLength++]=c;}

  if(cooling && int32_t(millis()-cooldown)>=0)cooling=false;
  if(processedGeneration!=linkGeneration){noInterrupts();processedGeneration=linkGeneration;activeConnection=requestedConnection;interrupts();resetLink();}
  if(secured()) {
    while(uart.available()) {
      int value=uart.read();if(value<0)break;uint8_t b=value;
      if(firstLength) {
        if(!haveHigh){inputHigh=b;haveHigh=true;continue;}
        expected=(uint16_t(inputHigh)<<8)|b;haveHigh=false;
        if(expected<5 || expected>sizeof(input)){Bluefruit.disconnect(activeConnection);resetLink();break;}
        firstLength=false;received=0;
      } else {
        input[received++]=b;
        if(received==expected){command();firstLength=true;received=0;}
      }
    }
    if(count) {
      auto& f=output[head];uint16_t remaining=f.len-f.offset;
      size_t written=uart.write(f.bytes+f.offset,remaining>20?20:remaining);
      f.offset+=written;if(f.offset==f.len){head=(head+1)%4;count--;}
    }
  }
  if(pending && !transmitting) {
    if(millis()-txStarted>2000){pending=false;if(hello && txSession==session)result(txId,0);}
    else if(!radio_driver.isReceiving()) {
      transmitting=radio_driver.startSendRaw(tx,txLen);pending=false;txStarted=millis();
      if(!transmitting && hello && txSession==session)result(txId,0);
    }
  }
  if(transmitting) {
    uint32_t airtime=radio_driver.getEstAirtimeFor(txLen);
    bool complete=radio_driver.isSendComplete();
    if(complete || millis()-txStarted>airtime*2+500) {
      radio_driver.onSendFinished();transmitting=false;cooldown=millis()+airtime*9;cooling=true;
      if(hello && txSession==session)result(txId,complete?1:0);
    }
  } else {
    uint8_t raw[255];int len=radio_driver.recvRaw(raw,sizeof(raw));
    if(len>0 && hello && secured() && count<3 && nwia::validRaw(raw,len,nwia::Mode::All))queue(0x83,0,raw,len);
  }
  radio_driver.loop();board.loop();
}
