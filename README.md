# Benedykt – głosowy asystent AI na Androida

Aplikacja mobilna (Android, Kotlin) z darmowym modelem Google Gemini, która:

- reaguje na komendę głosową **„Hej Benedykt”** (foreground service słucha w tle),
- prowadzi **naturalną konwersację po polsku** (głośny STT + TTS),
- **steruje telefonem**: otwiera aplikacje, dzwoni, wysyła SMS / WhatsApp,
  ustawia alarmy i minutniki, włącza latarkę, zmienia głośność, otwiera
  Wi-Fi / Bluetooth / ustawienia, wyszukuje w sieci, puszcza muzykę,
  otwiera Mapy, zwraca stan baterii itd.

Wszystko robi przez Gemini *function calling* – model sam decyduje, jaką akcję
wywołać, a aplikacja ją wykonuje.

## Jak uruchomić

1. **Pobierz darmowy klucz API Gemini** na <https://aistudio.google.com/apikey>
   (darmowy tier: `gemini-2.0-flash` – wystarcza do codziennego używania).
2. Otwórz projekt w Android Studio (Iguana / Koala lub nowszy).
   Gradle sam pobierze zależności.
3. Podłącz telefon (USB debugging) albo emulator z Androidem 7.0+ (API 24).
4. Kliknij **Run ▶** – aplikacja się zainstaluje.
5. Przy pierwszym uruchomieniu wklej klucz API w okienku, które się pojawi
   (można też otworzyć ustawienia 🛠 w prawym górnym rogu).
6. Nadaj wymagane uprawnienia: mikrofon, telefon, SMS, kontakty, aparat,
   powiadomienia.

## Jak używać

- Powiedz **„Hej Benedykt”** – aplikacja się otworzy i zacznie słuchać.
- Albo naciśnij duży przycisk mikrofonu.
- Po odpowiedzi asystenta mikrofon włącza się automatycznie, więc możesz
  prowadzić naturalną rozmowę bez klikania.

Przykładowe komendy:

- *„Zadzwoń do mamy”*
- *„Wyślij SMS do Ani, że spóźnię się 10 minut”*
- *„Ustaw alarm na siódmą rano”*
- *„Włącz latarkę”*
- *„Otwórz Spotify i puść Dawida Podsiadło”*
- *„Ile mam baterii?”*
- *„Nawiguj do Rynku w Krakowie”*
- *„Opowiedz mi jakiś dowcip”* (zwykła konwersacja)

## Struktura

```
app/src/main/java/com/benedykt/assistant/
  MainActivity.kt        – ekran aplikacji, UI, przepływ rozmowy
  GeminiClient.kt        – wywołania Gemini API + function calling
  PhoneController.kt     – wykonywanie akcji na telefonie
  VoiceEngine.kt         – rozpoznawanie mowy + synteza (TTS)
  WakeWordDetector.kt    – detekcja frazy „Hej Benedykt”
  WakeWordService.kt     – foreground service nasłuchujący w tle
  MessageAdapter.kt      – adapter czatu
  Message.kt             – model wiadomości
```

## Uwagi

- Klucz API trzymany jest lokalnie w `SharedPreferences` – nie jest nigdzie
  wysyłany poza Google Gemini.
- Detekcja wake word używa wbudowanego `SpeechRecognizer` Androida, więc
  działa bez zewnętrznych bibliotek i bez kosztów.
- Jeśli chcesz jeszcze bardziej niezawodnego *always-on* nasłuchu, możesz
  podłączyć `Porcupine` od Picovoice (darmowe dla prywatnego użytku) –
  kod w `WakeWordDetector` łatwo podmienić.
