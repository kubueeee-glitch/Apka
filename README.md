# Benedykt – głosowy asystent AI na Androida

Aplikacja (Android, Kotlin) z **darmowym Google Gemini 2.0 Flash**, która nie tylko
rozmawia – zarządza telefonem, pamięta Cię, uczy się Twoich komend, widzi
świat przez aparat i ma własny charakter.

## Co wyróżnia Benedykta na tle ChatGPT Voice i Gemini Live

| Funkcja | ChatGPT Voice | Gemini Live | **Benedykt** |
|---|---|---|---|
| Wake word „Hej …" | ❌ | ❌ | ✅ |
| Sterowanie telefonem (SMS, WiFi, alarm, latarka…) | ❌ | częściowo | ✅ 19+ akcji |
| **Trwała pamięć** między sesjami | częściowo | ❌ | ✅ (własny `MemoryStore`) |
| **Własne makra / skille** zdefiniowane głosem | ❌ | ❌ | ✅ |
| Osobowości (Kumpel / Stoik / Pirat…) | ❌ | ❌ | ✅ 5 trybów |
| Wizja (zdjęcie + analiza multimodalnie) | ✅ | ✅ | ✅ (Gemini 2.0 Flash) |
| Barge-in (przerwać wypowiedź mówiąc) | ✅ (nowy) | ✅ | ✅ (detekcja amplitudy) |
| Notatki głosowe z kategoriami | ❌ | ❌ | ✅ |
| Integracja ze schowkiem | ❌ | ❌ | ✅ |
| Działa offline dla wake word | — | — | ✅ (STT Androida) |
| Klucz API i dane **lokalnie** | ❌ | ❌ | ✅ |

## 8 unikalnych funkcji Benedykta

1. **Trwała pamięć (`MemoryStore`)** – Benedykt zapamiętuje Twoje imię, bliskich,
   preferencje, ulubione aplikacje, adresy. Gemini sam decyduje co warto
   zapamiętać (wywołuje funkcję `remember`). W każdym promptcie widzi pamięć,
   więc nie pyta o to samo dwa razy. Po resecie telefonu – nadal Cię zna.
2. **Własne skille / makra** – powiedz raz: *„Stwórz skill tryb nocny: wycisz
   telefon, wyłącz WiFi i ustaw alarm na siódmą"*. Benedykt zapisuje makro;
   od teraz mówisz tylko *„tryb nocny"* i wykonuje wszystko jedną komendą.
   Skille są edytowalne i widoczne w ustawieniach.
3. **Osobowości** – 5 charakterów: Kumpel, Profesjonalny, Stoik, Motywator,
   Pirat. Przełączasz głosowo *„przejdź w tryb pirata"* lub przyciskiem ★
   u góry ekranu. Każda osobowość ma inny styl mówienia (system prompt).
4. **Tryb wizji** – *„Co widzisz?"* albo *„Co to za roślina?"* otwiera aparat,
   zdjęcie leci do Gemini 2.0 Flash (multimodal), a on opisuje co widzi,
   czyta tekst (OCR), rozpoznaje przedmioty, tłumaczy napisy.
5. **Notatki głosowe z kategoriami** – *„zapisz w zakupach: mleko, chleb,
   papryka"*. Benedykt przypisuje kategorię i zapisuje. Pytasz *„co mam
   w notatkach z pracy?"* – przefiltruje i odczyta.
6. **Integracja ze schowkiem** – *„przetłumacz co mam w schowku"*, *„streszczę
   tekst ze schowka"*, *„skopiuj do schowka: …"*. Otwiera całkiem nowe
   przepływy pracy z treścią na telefonie.
7. **Barge-in (przerywanie mowy)** – w trakcie wypowiedzi Benedykta, jeśli
   zaczniesz mówić, mikrofon wykryje to po amplitudzie (`MediaRecorder`),
   TTS natychmiast milknie i włącza się rozpoznawanie. Naturalna rozmowa
   bez czekania.
8. **Poranny briefing jednym słowem** – *„Dzień dobry"* lub *„poranny briefing"*
   i Benedykt w jednej odpowiedzi poda godzinę, datę, stan baterii i status
   ładowania.

## Szybki start

1. **Pobierz darmowy klucz Gemini** → <https://aistudio.google.com/apikey>.
   Free tier (`gemini-2.0-flash`) w zupełności wystarczy do codziennego używania.
2. Otwórz projekt w Android Studio (Iguana / Koala lub nowszy).
3. Podłącz telefon (USB debugging) lub emulator z Androidem 7.0+ (API 24).
4. Kliknij **Run ▶**.
5. Wklej klucz w okienku, które wyskoczy.
6. Nadaj uprawnienia: mikrofon, telefon, SMS, kontakty, aparat, powiadomienia.

## Przykładowe komendy

### Codzienne
- *„Zadzwoń do mamy"*
- *„Wyślij SMS do Ani, że spóźnię się 10 minut"*
- *„Ustaw alarm na siódmą rano"*
- *„Włącz latarkę"* / *„Głośność na 30 procent"*
- *„Otwórz Spotify i puść Dawida Podsiadło"*
- *„Ile mam baterii?"*, *„Nawiguj do Rynku w Krakowie"*

### Pamięć i personalizacja
- *„Zapamiętaj, że mam na imię Kuba i mam labradora Cukrę"*
- *„Zapamiętaj, że moja mama ma numer +48 123 456 789"*
- *„Co o mnie pamiętasz?"*
- *„Zapomnij o mojej pracy"*

### Skille (makra)
- *„Stwórz skill tryb nocny: wycisz telefon, ustaw alarm na 7:00 i wyłącz WiFi"*
- *„Uruchom tryb nocny"*
- *„Jakie mam skille?"*
- *„Usuń skill tryb nocny"*

### Osobowości
- *„Przejdź w tryb stoika"*, *„Bądź moim kumplem"*, *„Tryb pirata"*

### Wizja
- *„Co widzisz?"* (robi zdjęcie i opisuje)
- *„Co to za roślina?"*
- *„Przeczytaj mi co jest na tej kartce"* (OCR)

### Notatki i schowek
- *„Zapisz notatkę: kupić prezent dla Kasi. Kategoria: prezenty"*
- *„Jakie mam notatki z zakupów?"*
- *„Przetłumacz mi co mam w schowku"*

### Briefing
- *„Dzień dobry"*, *„poranny briefing"*

## Struktura projektu

```
app/src/main/java/com/benedykt/assistant/
  MainActivity.kt        – ekran, przepływ rozmowy, kamera
  GeminiClient.kt        – klient API + function calling + multimodal
  PhoneController.kt     – wykonywanie akcji (19+ narzędzi)
  VoiceEngine.kt         – STT + TTS + barge-in
  WakeWordDetector.kt    – nasłuch „Hej Benedykt"
  WakeWordService.kt     – foreground service
  MemoryStore.kt         – trwała pamięć faktów
  SkillsStore.kt         – własne makra / skille
  NotesStore.kt          – notatki głosowe z kategoriami
  PersonaStore.kt        – osobowości
  MessageAdapter.kt      – czat
  Message.kt             – model wiadomości
```

## Prywatność

- Klucz API, pamięć, skille, notatki – wszystko lokalnie w `SharedPreferences`.
- Aplikacja nie wysyła nic na serwery poza zapytaniami do Gemini API
  (wypowiedź + opcjonalne zdjęcie).
- Nasłuch wake word odbywa się na urządzeniu przez Android `SpeechRecognizer`.

## Dodatkowe integracje (zaimplementowane)

### 📬 Dostęp do powiadomień (`NotificationListenerService`)
Włącz w Ustawieniach → Powiadomienia → Dostęp do powiadomień → Benedykt.
- *„Co mam w powiadomieniach?"*
- *„Odpowiedz Ani: jestem w drodze"* (używa `RemoteInput` – działa dla Messengera,
  WhatsAppa, Signala, domyślnego SMS itd.)

### 👀 Dostępność – Benedykt widzi i dotyka ekran (`AccessibilityService`)
Włącz w Ustawieniach → Dostępność → Benedykt.
- *„Co jest na ekranie?"* – czyta wszystkie widoczne napisy.
- *„Kliknij w Zaloguj"* – sam znajdzie przycisk i go tapnie (również gestem,
  jeśli element nie jest bezpośrednio klikalny).
- *„Przewiń w dół"*, *„wciśnij home"*, *„wróć"*.

### 🏠 Home Assistant
Ustawienia → Home Assistant → wklej adres (`http://homeassistant.local:8123`)
i Long-Lived Access Token (Profil → Security).
- *„Włącz światło w salonie"* (Gemini sam dobiera `light.salon` i `light.turn_on`)
- *„Jaka jest temperatura w sypialni?"*
- *„Lista świateł"* → Benedykt woła `ha_list_entities(domain=light)`.

### 🚗 Tryb kierowcy
Ustawienia → Tryb kierowcy (lub *„włącz tryb kierowcy"*).
- Wielki przycisk mikrofonu na całym ekranie.
- Ekran nie gaśnie (`FLAG_KEEP_SCREEN_ON`).
- Automatyczny restart słuchania po każdej odpowiedzi.
- **Auto-czytanie powiadomień na głos** – broadcast z listenera trafia
  do aktywności, która odczytuje je przez TTS (wymaga włączenia dostępu do
  powiadomień).

## Pomysły na kolejne iteracje

- Odcisk głosu (tylko właściciel budzi) przez porównanie MFCC nagrań.
- Lokalny Whisper.cpp zamiast Google STT (lepsza polszczyzna, offline).
- Porcupine / openWakeWord dla niezawodnego always-on wake word.
- Voice journal – automatyczna transkrypcja głosowych wpisów z datą.
- Obsługa Matter przez ThreadNetwork + CommissioningClient.
- Widget ekranu głównego z szybkim dostępem do mikrofonu.
