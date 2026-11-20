# Otto TV

**[English](#english)** | **[Português](#português)**

---

## English

My TV is old and weak. Its "smart" side takes forever to open anything,
freezes in the middle of an episode, and using YouTube on it is a pain. At
the same time I had an old Acer laptop sitting around with nothing to do.

So I plugged the laptop into the TV and put Android TV on it. It technically
worked, but it was bad: it took ages to boot, the sound came out of the
wrong place, there was no remote, and half the screens expected a TV remote
instead of a laptop. Otto TV started as me fixing that, and it turned into a
lot more.

### What you can do with it

- **Watch stuff:** YouTube (the Android TV version), Stremio, and whatever
  else you install.
- **Play games with your phone as the controller.** Beach Buggy Racing runs
  really well and it's a lot of fun.
- **Install apps:** Aurora Store (the Play Store catalog, no Google account
  needed) and F-Droid come with it.
- **Browse the web** with DuckDuckGo, using the phone as keyboard and
  touchpad.
- **Use the laptop's keyboard and mouse** if you'd rather. The shortcuts
  make sense on a TV: Esc goes back, F2 opens the quick panel.
- **See fewer ads.** A switch in the quick panel, on from the start, blocks
  ads in apps and websites. YouTube's still get through.

Streaming apps are hit and miss. Disney+, Crunchyroll, Twitch and Globoplay
work (Globoplay's picture only shows on the laptop screen for now). Netflix,
Prime Video, Max and Apple TV don't: Netflix refuses devices Google hasn't
certified, and I'm still looking into the others.

### What's actually in here

This isn't Android TV with a new wallpaper. Some of what went into it:

- **A home screen written from scratch.** The app draws everything itself,
  built to stay smooth on a laptop GPU from 2012: janky frames went from 30%
  to 4%.
- **A phone remote that works everywhere.** No app to install. The TV
  creates a virtual keyboard and mouse, so Android thinks a USB keyboard is
  plugged in. That's why it works in games, in settings, in any app. You pair
  by scanning a QR code, and it finds the TV by name (`otto-tv.local`) even
  when the router changes its address.
- **Boots in 40 seconds on an old hard drive** (it was 77). After a boot it
  notes what the system read, and on the next one it reads all of that in
  disk order before Android asks for it.
- **Apps you go back to open in 0.06 s** instead of 1.1 s.
- **The sound just goes to the TV.** It reads the HDMI sensors to find the
  output with a TV plugged in. Nothing to set up.
- **It updates itself** from this repository's releases. Updates are signed,
  so the TV only installs what I published.
- **Protected video on old CPUs.** Apps with protected video made Android's
  DRM crash on processors from before 2013, which lack one instruction
  (MOVBE). A tiny library now does that instruction in software, only on
  those CPUs. That's what got Disney+ playing.
- **Its own installer, boot menu and boot animation**, with Otto the octopus.
- **Tested.** Over 250 automated tests run on every change, and everything
  above was measured on the real thing.

### What you need

- A 64-bit PC or laptop from around 2010 or newer (Intel Core i3/i5/i7, or AMD
  from 2011 on). Core 2 Duo and older won't work.
- 2 GB of RAM (4 GB is better).
- An 8 GB USB stick.
- Heads up: installing erases the computer's disk.

### How to try it

Download the ISO from [Google Drive](https://drive.google.com/file/d/1aCdLakNSqOuaPvUmTtjlDD6mi7I2ikdl/view). It's bigger than the 2 GB
GitHub allows per file, so it lives there. Drive warns it can't scan a file
this big for viruses: that's normal, download it anyway. Each
[release](https://github.com/gmoreno-dev/OttoTV/releases) here lists the
ISO's SHA-256 so you can check the download. You can also build it yourself
([how](docs/technical.md#build)). Then:

1. Write the ISO to a USB stick (Rufus, balenaEtcher or Ventoy).
2. Plug the computer into the TV and boot from the stick.
3. Try it as is. Liked it? Pick "Instalar o Otto TV neste computador" in the
   boot menu (the menus are in Portuguese for now).

### Status

Done, for now: it does what I wanted, and my old TV got a second life. It
has only run on one machine, my Acer hooked up to a Samsung TV, so other
PCs are uncharted. If you try it on yours, open an issue and tell me how it
went, especially if something breaks. I fix bugs when I can, and installed
TVs get the fixes on their own.

### More details

How it works, how to build it, boot options and everything else:
[docs/technical.md](docs/technical.md).

Otto TV is built on [LineageOS TV x86](https://github.com/LineageOS-TV-x86),
which builds on LineageOS, [Bliss OS](https://blissos.org) and Android-x86.
I don't rebuild Android: I take their image and add my stuff on top. The
bundled apps come from [F-Droid](https://f-droid.org).

---

## Português

Minha TV é velha e fraca. A parte "smart" dela demora uma eternidade pra
abrir qualquer coisa, trava no meio do episódio, e usar o YouTube nela é um
sofrimento. Ao mesmo tempo, eu tinha um notebook Acer antigo parado, sem uso
nenhum.

Aí liguei o notebook na TV e coloquei Android TV nele. Funcionar, funcionava,
mas era ruim: demorava muito pra ligar, o som saía no lugar errado, não
tinha controle, e metade das telas esperava um controle remoto em vez de um
notebook. O Otto TV começou comigo arrumando isso, e virou bem mais que isso.

### O que dá pra fazer com ele

- **Assistir:** YouTube (a versão de Android TV), Stremio e o que mais você
  instalar.
- **Jogar usando o celular como controle.** O Beach Buggy Racing roda muito
  bem e é divertido demais.
- **Instalar apps:** já vem com a Aurora Store (o catálogo da Play Store, sem
  precisar de conta Google) e o F-Droid.
- **Navegar na internet** com o DuckDuckGo, usando o celular como teclado e
  touchpad.
- **Usar o teclado e o mouse do notebook**, se preferir. Os atalhos fazem
  sentido numa TV: Esc volta, F2 abre o painel rápido.
- **Ver menos anúncios.** Um botão no painel rápido, que já vem ligado,
  bloqueia anúncios em apps e sites. Os do YouTube ainda passam.

Apps de streaming: uns funcionam, outros não. Disney+, Crunchyroll, Twitch e
Globoplay funcionam (por enquanto a imagem do Globoplay só aparece na tela do
notebook). Netflix, Prime Video, Max e Apple TV não: a Netflix recusa
aparelhos que o Google não certificou, e os outros eu ainda tô investigando.

### O que tem de verdade aqui dentro

Não é um Android TV com papel de parede novo. Um pouco do que foi feito:

- **Uma tela inicial feita do zero.** O próprio app desenha tudo, pensado pra
  rodar liso numa placa de vídeo de notebook de 2012: os quadros travados
  caíram de 30% pra 4%.
- **Um controle no celular que funciona em tudo.** Sem instalar app. A TV
  cria um teclado e um mouse virtuais, então o Android acha que tem um
  teclado USB ligado. Por isso funciona em jogo, nas configurações, em
  qualquer app. Você pareia escaneando um QR code, e ele acha a TV pelo nome
  (`otto-tv.local`) mesmo quando o roteador muda o endereço dela.
- **Liga em 40 segundos num HD velho** (antes eram 77). Depois de um boot ele
  anota o que o sistema leu, e no próximo lê tudo isso na ordem em que está
  no disco, antes de o Android pedir.
- **Apps que você já abriu voltam em 0,06 s** em vez de 1,1 s.
- **O som vai pra TV sozinho.** Ele lê os sensores do HDMI pra achar a saída
  que tem uma TV ligada. Nada pra configurar.
- **Ele se atualiza sozinho** pelas releases deste repositório. As
  atualizações são assinadas, então a TV só instala o que eu publiquei.
- **Vídeo protegido em processador antigo.** Apps com vídeo protegido
  derrubavam o DRM do Android em processadores de antes de 2013, que não têm
  uma instrução (MOVBE). Uma biblioteca pequenininha agora faz essa
  instrução por software, só nesses processadores. Foi isso que fez o Disney+
  rodar.
- **Instalador, menu de boot e animação de inicialização próprios**, com o
  Otto, o polvo.
- **Testado.** Mais de 250 testes automáticos rodam a cada mudança, e tudo aí
  em cima foi medido no aparelho de verdade.

### O que você precisa

- Um PC ou notebook 64 bits de 2010 pra cá (Intel Core i3/i5/i7, ou AMD de
  2011 em diante). Core 2 Duo e mais antigos não funcionam.
- 2 GB de RAM (4 GB é melhor).
- Um pendrive de 8 GB.
- Atenção: instalar apaga o disco do computador.

### Como testar

Baixa a ISO no [Google Drive](https://drive.google.com/file/d/1aCdLakNSqOuaPvUmTtjlDD6mi7I2ikdl/view). Ela passa dos 2 GB que o GitHub aceita
por arquivo, então fica lá. O Drive avisa que não consegue verificar vírus
num arquivo desse tamanho: é normal, baixa mesmo assim. Cada
[release](https://github.com/gmoreno-dev/OttoTV/releases) daqui mostra o
SHA-256 da ISO, pra você conferir o download. Também dá pra gerar a sua
([como](docs/technical.md#build)). Depois:

1. Grava a ISO num pendrive (Rufus, balenaEtcher ou Ventoy).
2. Liga o computador na TV e dá boot pelo pendrive.
3. Testa direto do pendrive. Gostou? Escolhe "Instalar o Otto TV neste
   computador" no menu de boot.

### Status

Pronto, por enquanto: ele faz o que eu queria, e minha TV velha ganhou uma
segunda vida. Até agora só rodou numa máquina, o meu Acer ligado numa TV
Samsung, então outros PCs são território desconhecido. Se você testar no
seu, abre uma issue e me conta como foi, principalmente se algo quebrar. Eu
corrijo bug quando dá, e as TVs instaladas recebem as correções sozinhas.

### Mais detalhes

Como funciona, como gerar a ISO, opções de boot e o resto (em inglês):
[docs/technical.md](docs/technical.md).

O Otto TV é feito em cima do
[LineageOS TV x86](https://github.com/LineageOS-TV-x86), que por sua vez vem
do LineageOS, do [Bliss OS](https://blissos.org) e do Android-x86. Eu não
recompilo o Android: pego a imagem deles e coloco as minhas coisas por cima.
Os apps que vêm junto são do [F-Droid](https://f-droid.org).
