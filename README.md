# PinDIY Bot

Automatyczny bot do komentowania wątków na forum pindiy.com (Discuz! CMS).

## Funkcje
- Auto-login + ponowne logowanie przy wygaśnięciu sesji
- Omijanie Cloudflare przez kopiowanie ciasteczek z prawdziwego Chrome
- AI generowanie komentarzy (GPT-4) na podstawie treści posta
- Wykrywanie duplikatów – nie komentuje dwa razy tego samego wątku
- Blacklista wątków do pominięcia (`blacklist.txt`)
- Historia skomentowanych wątków (`data/commented-threads.csv`)
- Cooldown 50 sekund między postami
- Limit czasowy działania bota (okno dialogowe)

## Wymagania
- Java 17 (Temurin)
- Maven
- Chrome (zainstalowany na komputerze)

## Konfiguracja
1. Skopiuj `config.properties.example` → `config.properties`
2. Uzupełnij login, hasło i komentarz
3. (Opcjonalnie) Ustaw klucz OpenAI w `AICommentGenerator.java`

## Uruchomienie
```bash
./run.sh
```

## Struktura
```
src/main/java/com/pindiy/bot/
  ForumCommenter.java     – główna klasa bota
  BotDriver.java          – konfiguracja Selenium + Cloudflare bypass
  CommentLog.java         – historia i blacklista
  Config.java             – ładowanie konfiguracji
  AICommentGenerator.java – generowanie komentarzy przez GPT-4
blacklist.txt             – wątki do pominięcia
data/                     – lokalne logi (gitignored)
```
