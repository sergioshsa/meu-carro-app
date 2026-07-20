# Meu Carro — registro de KM (Pessoal x Uber)

App Android que registra, por GPS, quantos quilômetros você roda em **modo Pessoal** e em **modo Uber**, separadamente. Serve para, ao encher o tanque, saber quanto daquele combustível foi gasto em cada finalidade (e o custo por km de cada um).

---

## O que o app faz

- Você entra no carro e diz por voz (ou toca um botão) qual modo quer: **Pessoal** ou **Uber**.
- Ele liga o GPS em segundo plano (com uma notificação fixa) e vai somando os km daquele modo, mesmo com a tela apagada.
- Quando termina, você para o registro.
- Ao **abastecer**, você registra litros e valor pago. A partir daí o app conta quanto rodou de Pessoal e de Uber **naquele tanque**, o consumo (km/L) e o custo por km de cada modo.

---

## PARTE 1 — Como gerar o APK (sem instalar nada no seu PC)

O código completo está nesta pasta. O jeito mais fácil de transformar em APK é usar o **GitHub Actions**, que compila na nuvem de graça e te devolve o APK pronto. Você só precisa de uma conta no GitHub (grátis).

1. Crie uma conta em https://github.com (se ainda não tiver).
2. Clique em **New repository**. Dê um nome, ex.: `meu-carro`, e clique em **Create repository**.
3. Na página do repositório vazio, clique em **uploading an existing file**.
4. Arraste para lá **todo o conteúdo desta pasta** (as pastas `app`, `.github`, e os arquivos `build.gradle`, `settings.gradle`, `gradle.properties`, `.gitignore`). Clique em **Commit changes**.
5. Vá na aba **Actions**. O fluxo **Build APK** começa sozinho. Espere terminar (2 a 5 min, fica verde).
6. Baixe o APK em:
   - Aba **Releases** → `app-debug.apk`; ou
   - Aba **Actions** → build concluído → **Artifacts** → **MeuCarro-APK**.
7. Passe o `app-debug.apk` para o celular (WhatsApp para você mesmo, Drive, cabo USB).

> Alternativa: abrir esta pasta no Android Studio e usar **Build > Build APK(s)**.

---

## PARTE 2 — Instalar no celular

1. Abra o `app-debug.apk` no celular.
2. Permita instalar de "fonte desconhecida" quando pedir.
3. Confirme. Aparece o app **Meu Carro** (ícone dourado).

### Permissões importantes (para funcionar com a tela apagada)

- **Localização:** escolha **"Permitir o tempo todo"**.
- **Notificações:** permita.
- **Bateria:** Configurações → Apps → Meu Carro → Bateria → **"Sem restrições"** (desative otimização). Evita que o Android mate o registro em viagens longas.

---

## PARTE 3 — Comandos de voz ("Ok Google")

Após instalar, aparecem **dois ícones extras**: **"Meu Carro Uber"** e **"Meu Carro Pessoal"**. Cada um já inicia o registro naquele modo ao abrir.

### Jeito simples (funciona de imediato)

- **"Ok Google, abrir Meu Carro Uber"** → inicia modo Uber.
- **"Ok Google, abrir Meu Carro Pessoal"** → inicia modo Pessoal.

### Para usar a SUA frase exata ("entrar no modo Uber")

Crie uma **Rotina** no Google Assistente (uma vez só):

1. App **Google** → seu perfil → **Configurações** → **Google Assistente** → **Rotinas** (ou app Google Home → Rotinas).
2. Toque em **+**.
3. Em **Quando eu disser...**: `entrar no modo Uber`.
4. Em **Assistente vai...** → **Adicionar ação** → **Abrir apps** → **Meu Carro Uber**.
5. Salve. Repita para `entrar no modo pessoal` → **Meu Carro Pessoal**.

Aí você fala **"Ok Google, entrar no modo Uber"** e ele abre já registrando.

---

## PARTE 4 — Uso no dia a dia

1. Vai trabalhar de Uber → "abrir Meu Carro Uber". Dirija.
2. Terminou → app → **PARAR REGISTRO** (ou "Parar" na notificação).
3. Uso pessoal → "abrir Meu Carro Pessoal".
4. **Encheu o tanque?** App → **REGISTRAR ABASTECIMENTO** → litros e valor. Marca o ponto zero do tanque.
5. A tela **TANQUE ATUAL** mostra desde o último abastecimento: km Pessoal, km Uber, consumo (km/L) e quanto do dinheiro foi para cada modo.

---

## Observações

- APK de **teste (debug)**, para uso pessoal (não passa pela Play Store — daí a "fonte desconhecida").
- Precisão depende do sinal de GPS; o app ignora leituras ruins.
- Dados ficam só no celular (nada vai para a internet).
- Dá para publicar na Play Store / assinar o APK depois — é só pedir.

---

Package: `com.sergiosantos.meucarro` · minSdk 24 (Android 7+) · targetSdk 34.
