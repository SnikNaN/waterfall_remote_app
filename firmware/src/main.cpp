
// ===== ESP8266 + FastLED REST LED Controller v3 (Preview + OTA) =====
// D2, 99 диодов, COLOR_ORDER=BRG
// OTA: ArduinoOTA + HTTP /update (c BASIC-авторизацией)

#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266mDNS.h>
#include <ESP8266WebServer.h>
#include <LittleFS.h>
#include <FastLED.h>
#include <ArduinoOTA.h>
#include <ESP8266HTTPUpdateServer.h>

// ---------- Wi-Fi ----------
#ifndef WIFI_SSID
#define WIFI_SSID   "YOUR_WIFI_SSID"
#endif
#ifndef WIFI_PASS
#define WIFI_PASS   "YOUR_WIFI_PASSWORD"
#endif

// ---------- HTTP Update auth ----------
#ifndef OTA_HTTP_USER
#define OTA_HTTP_USER "admin"
#endif
#ifndef OTA_HTTP_PASS
#define OTA_HTTP_PASS "changeme"
#endif

// ---------- LED strip ----------
#define DATA_PIN     D2
#define NUM_LEDS     99
#define LED_TYPE     WS2812B
#define COLOR_ORDER  BRG

// ---------- FS ----------
#define FILE_STATE   "/state_v3.bin"

// ---------- HTTP ----------
ESP8266WebServer server(80);
ESP8266HTTPUpdateServer httpUpdater;

// ---------- Model ----------
struct __attribute__((packed)) StateHeader {
  uint32_t magic    = 0xBEEFCAFE;
  uint16_t version  = 3;
  uint16_t num_leds = NUM_LEDS;
  uint8_t  gbright  = 128; // global 0..255
};
StateHeader state;

struct MyRGB { uint8_t r,g,b; };

CRGB   *leds;      // буфер вывода FastLED
MyRGB  *baseRGB;   // базовый цвет (тон/оттенок)
uint8_t*localB;    // локальная яркость 0..255

// ---------- Preview selection ----------
bool     selActive = false;
bool     selDirty  = false;
uint16_t selStart = 0, selEnd = 0, selLen = 0;
MyRGB   *selBackupRGB = nullptr;
uint8_t *selBackupLB  = nullptr;

// ---------- Utils ----------
inline void withCORS(){
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}
void handleOptions(){ withCORS(); server.send(204); }

bool parseHexColor(const String& hex, uint8_t &r, uint8_t &g, uint8_t &b){
  String h = hex;
  if (h.length()==7 && h[0]=='#') h = h.substring(1);
  if (h.length()!=6) return false;
  char *endptr=nullptr;
  long v = strtol(h.c_str(), &endptr, 16);
  if (*endptr!=0) return false;
  r=(v>>16)&0xFF; g=(v>>8)&0xFF; b=v&0xFF; return true;
}

inline void clampRange(uint16_t &s, uint16_t &e){
  if (s >= NUM_LEDS) s = NUM_LEDS-1;
  if (e >= NUM_LEDS) e = NUM_LEDS-1;
  if (s > e) std::swap(s,e);
}

// финальный вывод: first = baseRGB × localB, global применяется через FastLED.setBrightness()
inline void applyToStrip(){
  FastLED.setBrightness(state.gbright);
  for (uint16_t i=0;i<NUM_LEDS;i++){
    // scale8_video не «съедает» очень тусклые значения
    uint8_t r = scale8_video(baseRGB[i].r, localB[i]);
    uint8_t g = scale8_video(baseRGB[i].g, localB[i]);
    uint8_t b = scale8_video(baseRGB[i].b, localB[i]);
    leds[i].setRGB(r,g,b);
  }
  FastLED.show();
}

void freeSelection(){
  if (selBackupRGB) { free(selBackupRGB); selBackupRGB = nullptr; }
  if (selBackupLB)  { free(selBackupLB);  selBackupLB  = nullptr; }
  selActive = false; selDirty = false; selLen = 0;
}

void restoreSelection(){ // откат превью
  if (!selActive || !selBackupRGB || !selBackupLB) return;
  for (uint16_t i=0;i<selLen;i++){
    baseRGB[selStart + i] = selBackupRGB[i];
    localB [selStart + i] = selBackupLB [i];
  }
  applyToStrip();
  freeSelection();
}

bool createSelection(uint16_t s, uint16_t e){
  if (selActive && selDirty) restoreSelection();
  else if (selActive) freeSelection();

  clampRange(s,e);
  selStart = s; selEnd = e; selLen = e - s + 1;

  selBackupRGB = (MyRGB*)malloc(selLen * sizeof(MyRGB));
  selBackupLB  = (uint8_t*)malloc(selLen * sizeof(uint8_t));
  if (!selBackupRGB || !selBackupLB){
    freeSelection();
    return false;
  }
  for (uint16_t i=0;i<selLen;i++){
    selBackupRGB[i] = baseRGB[selStart + i];
    selBackupLB [i] = localB [selStart + i];
  }
  selActive = true; selDirty = false;
  return true;
}

// ---------- Handlers ----------
void handleSelect(){
  withCORS();
  if (!server.hasArg("start") || !server.hasArg("end")){
    server.send(400,"application/json","{\"ok\":false,\"err\":\"missing params\"}");
    return;
  }
  uint16_t s = server.arg("start").toInt();
  uint16_t e = server.arg("end").toInt();
  clampRange(s,e);
  if (!createSelection(s,e)){
    server.send(500,"application/json","{\"ok\":false,\"err\":\"alloc\"}");
    return;
  }

  bool doBlink = server.hasArg("blink") ? (server.arg("blink").toInt()!=0) : false;
  if (doBlink){
    for (uint8_t t=0;t<2;t++){
      for (uint16_t i=selStart;i<=selEnd;i++){ baseRGB[i]={255,255,255}; localB[i]=255; }
      applyToStrip(); delay(120);
      for (uint16_t i=selStart;i<=selEnd;i++){ baseRGB[i]={0,0,0};     localB[i]=255; }
      applyToStrip(); delay(120);
    }
    for (uint16_t i=0;i<selLen;i++){ baseRGB[selStart+i]=selBackupRGB[i]; localB[selStart+i]=selBackupLB[i]; }
    applyToStrip();
  }
  server.send(200,"application/json","{\"ok\":true,\"selected\":true}");
}

void handleSetPreview(){
  withCORS();
  if (!selActive){ server.send(409,"application/json","{\"ok\":false,\"err\":\"no selection\"}"); return; }
  if (!server.hasArg("hex")){ server.send(400,"application/json","{\"ok\":false,\"err\":\"missing hex\"}"); return; }

  uint8_t r,g,b;
  if (!parseHexColor(server.arg("hex"), r,g,b)){
    server.send(400,"application/json","{\"ok\":false,\"err\":\"bad hex\"}"); return;
  }

  bool hasLB = server.hasArg("lbright");
  uint8_t lval = 255;
  if (hasLB){
    int v = server.arg("lbright").toInt();
    if (v<0) v=0; if (v>255) v=255; lval=(uint8_t)v;
  }

  for (uint16_t i=selStart;i<=selEnd;i++){
    baseRGB[i] = {r,g,b};
    if (hasLB) localB[i] = lval;
  }
  selDirty = true;
  applyToStrip();
  server.send(200,"application/json","{\"ok\":true}");
}

void handleLocalBrightness(){
  withCORS();
  if (!selActive){ server.send(409,"application/json","{\"ok\":false,\"err\":\"no selection\"}"); return; }
  if (!server.hasArg("value")){ server.send(400,"application/json","{\"ok\":false,\"err\":\"missing value\"}"); return; }
  int v = server.arg("value").toInt();
  if (v<0) v=0; if (v>255) v=255;
  for (uint16_t i=selStart;i<=selEnd;i++) localB[i]=(uint8_t)v;
  selDirty = true;
  applyToStrip();
  server.send(200,"application/json","{\"ok\":true}");
}

void handleCancel(){
  withCORS();
  if (selActive) restoreSelection();
  server.send(200,"application/json","{\"ok\":true}");
}

void handleBlink(){
  withCORS();
  uint16_t s = server.hasArg("start")? server.arg("start").toInt():0;
  uint16_t e = server.hasArg("end")  ? server.arg("end").toInt()  :0;
  uint8_t times = server.hasArg("times")? (uint8_t)server.arg("times").toInt():2;
  uint16_t ms = server.hasArg("ms")? (uint16_t)server.arg("ms").toInt():180;
  clampRange(s,e);

  for (uint8_t t=0;t<times;t++){
    for (uint16_t i=s;i<=e;i++){ leds[i]=CRGB(255,255,255); }
    FastLED.setBrightness(255); FastLED.show(); delay(ms);
    for (uint16_t i=s;i<=e;i++){ leds[i]=CRGB(0,0,0); }
    FastLED.show(); delay(ms);
  }
  applyToStrip();
  server.send(200,"application/json","{\"ok\":true}");
}

void handleGlobalBrightness(){
  withCORS();
  if (!server.hasArg("value")){
    server.send(400,"application/json","{\"ok\":false,\"err\":\"missing value\"}");
    return;
  }
  int v = server.arg("value").toInt();
  if (v<0) v=0; if (v>255) v=255;
  state.gbright=(uint8_t)v;
  applyToStrip();
  server.send(200,"application/json","{\"ok\":true}");
}

void handleSave(){
  withCORS();
  File f = LittleFS.open(FILE_STATE,"w");
  if (!f){ server.send(500,"application/json","{\"ok\":false}"); return; }

  if (f.write((uint8_t*)&state,sizeof(state)) != sizeof(state)) { f.close(); server.send(500,"application/json","{\"ok\":false}"); return; }
  size_t rgbBytes = NUM_LEDS*sizeof(MyRGB);
  size_t lbBytes  = NUM_LEDS*sizeof(uint8_t);
  if (f.write((uint8_t*)baseRGB, rgbBytes)!=rgbBytes){ f.close(); server.send(500,"application/json","{\"ok\":false}"); return; }
  if (f.write((uint8_t*)localB , lbBytes )!=lbBytes ){ f.close(); server.send(500,"application/json","{\"ok\":false}"); return; }
  f.close();

  freeSelection(); // превью стало постоянным
  server.send(200,"application/json","{\"ok\":true}");
}

void handleLoad(){
  withCORS();
  File f = LittleFS.open(FILE_STATE,"r");
  if (!f){ server.send(404,"application/json","{\"ok\":false,\"err\":\"no file\"}"); return; }

  StateHeader hdr;
  if (f.read((uint8_t*)&hdr,sizeof(hdr))!=sizeof(hdr) ||
      hdr.magic!=0xBEEFCAFE || hdr.version!=3 || hdr.num_leds!=NUM_LEDS){
    f.close(); server.send(400,"application/json","{\"ok\":false,\"err\":\"bad header\"}"); return;
  }
  state = hdr;
  size_t rgbBytes = NUM_LEDS*sizeof(MyRGB);
  size_t lbBytes  = NUM_LEDS*sizeof(uint8_t);
  if (f.read((uint8_t*)baseRGB, rgbBytes)!=rgbBytes){ f.close(); server.send(400,"application/json","{\"ok\":false,\"err\":\"short rgb\"}"); return; }
  if (f.read((uint8_t*)localB , lbBytes )!=lbBytes ){ f.close(); server.send(400,"application/json","{\"ok\":false,\"err\":\"short lb\"}"); return; }
  f.close();

  freeSelection();
  applyToStrip();
  server.send(200,"application/json","{\"ok\":true}");
}

void handleState(){
  withCORS();
  String json = "{\"ok\":true,\"num_leds\":"+String(NUM_LEDS)+
                ",\"bright\":"+String(state.gbright)+
                ",\"selection\":{";
  if (selActive){
    json += "\"active\":true,\"start\":"+String(selStart)+",\"end\":"+String(selEnd)+",\"dirty\":"+(selDirty?String("true"):String("false"));
  } else {
    json += "\"active\":false";
  }
  json += "}}";
  server.send(200,"application/json",json);
}

void handleReboot(){
  withCORS();
  server.send(200,"application/json","{\"ok\":true,\"rebooting\":true}");
  delay(200);
  ESP.restart();
}

// ---------- Wi-Fi ----------
void connectWiFi(){
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  unsigned long t0 = millis();
  while (WiFi.status()!=WL_CONNECTED && millis()-t0<15000){ delay(250); }
  if (WiFi.status()!=WL_CONNECTED){
    WiFi.mode(WIFI_AP);
    WiFi.softAP("LED-CTRL","12345678");
  }
}

// ---------- OTA ----------
void setupArduinoOTA(){
  ArduinoOTA.setHostname("led");
  // ArduinoOTA.setPassword("your_ota_password"); // если нужен пароль
  ArduinoOTA.onStart([](){ FastLED.clear(true); });
  ArduinoOTA.begin();
}

void setup(){
  baseRGB = (MyRGB*) malloc(NUM_LEDS * sizeof(MyRGB));
  localB  = (uint8_t*)malloc(NUM_LEDS * sizeof(uint8_t));
  leds    = (CRGB*)   malloc(NUM_LEDS * sizeof(CRGB));
  if (!baseRGB || !localB || !leds){ while(true){ delay(1000);} }

  for (uint16_t i=0;i<NUM_LEDS;i++){ baseRGB[i]={0,0,0}; localB[i]=255; leds[i]=CRGB::Black; }

  FastLED.addLeds<LED_TYPE, DATA_PIN, COLOR_ORDER>(leds, NUM_LEDS);
  FastLED.setBrightness(state.gbright);
  FastLED.clear(true);

  LittleFS.begin();
  connectWiFi();

  MDNS.begin("led");
  MDNS.addService("http","tcp",80);

  // HTTP Update (/update) с базовой авторизацией
  httpUpdater.setup(&server, "/update", OTA_HTTP_USER, OTA_HTTP_PASS);

  // REST маршруты
  server.on("/", HTTP_GET, [](){ withCORS(); server.send(200,"text/plain","ESP8266 LED controller v3 (FastLED + preview + OTA)"); });
  server.onNotFound([](){ withCORS(); server.send(404,"application/json","{\"ok\":false,\"err\":\"not found\"}"); });

  // CORS preflight
  server.on("/select",     HTTP_OPTIONS, handleOptions);
  server.on("/set",        HTTP_OPTIONS, handleOptions);
  server.on("/lbright",    HTTP_OPTIONS, handleOptions);
  server.on("/cancel",     HTTP_OPTIONS, handleOptions);
  server.on("/blink",      HTTP_OPTIONS, handleOptions);
  server.on("/brightness", HTTP_OPTIONS, handleOptions);
  server.on("/save",       HTTP_OPTIONS, handleOptions);
  server.on("/load",       HTTP_OPTIONS, handleOptions);
  server.on("/state",      HTTP_OPTIONS, handleOptions);
  server.on("/reboot",     HTTP_OPTIONS, handleOptions);

  // API
  server.on("/select",     HTTP_GET, handleSelect);
  server.on("/set",        HTTP_GET, handleSetPreview);
  server.on("/lbright",    HTTP_GET, handleLocalBrightness);
  server.on("/cancel",     HTTP_GET, handleCancel);
  server.on("/blink",      HTTP_GET, handleBlink);
  server.on("/brightness", HTTP_GET, handleGlobalBrightness);
  server.on("/save",       HTTP_GET, handleSave);
  server.on("/load",       HTTP_GET, handleLoad);
  server.on("/state",      HTTP_GET, handleState);
  server.on("/reboot",     HTTP_GET, handleReboot);

  server.begin();

  setupArduinoOTA();

  // автозагрузка сцены при старте
  if (LittleFS.exists(FILE_STATE)){
    File f = LittleFS.open(FILE_STATE,"r");
    StateHeader hdr;
    if (f && f.read((uint8_t*)&hdr,sizeof(hdr))==sizeof(hdr) &&
        hdr.magic==0xBEEFCAFE && hdr.version==3 && hdr.num_leds==NUM_LEDS){
      state = hdr;
      size_t rgbBytes = NUM_LEDS*sizeof(MyRGB);
      size_t lbBytes  = NUM_LEDS*sizeof(uint8_t);
      if (f.read((uint8_t*)baseRGB, rgbBytes)==rgbBytes &&
          f.read((uint8_t*)localB , lbBytes )==lbBytes ){
        applyToStrip();
      }
    }
    f.close();
  }
}

void loop(){
  server.handleClient();
  MDNS.update();
  ArduinoOTA.handle();
}
