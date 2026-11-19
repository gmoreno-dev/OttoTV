# Otto TV

**[English](#english)** | **[Português](#português)**

---

## English

My TV is old and weak. Its "smart" side takes forever to open anything,
freezes in the middle of an episode, and using YouTube on it is a pain. At
the same time I had an old Acer laptop sitting around with nothing to do.

So I plugged the laptop into the TV and put Android TV on it. It worked,
sort of: it took ages to boot, the sound came out of the wrong place, there
was no remote, and half the screens expected a TV remote instead of a laptop.
Otto TV is me fixing all of that, one thing at a time.

On my Acer (an old i5 with a hard drive) it now boots in about 40 seconds
(it was 77) and the home screen runs smooth.

### What it does

- Turns an old PC or laptop into an Android TV. Plug it into the TV over HDMI
  and you're good.
- Its own home screen: your favorites up top, every app one click away.
- Your phone is the remote. Scan the QR code on the TV and that's it, no app
  to install (iPhone and Android).
- The sound goes to the TV on its own. Want the laptop speakers instead? One
  click.
- The laptop screen can go dark while you watch on the TV.
- It updates itself: new versions show up in the quick panel (F2).
- Comes with F-Droid, Aurora Store and DuckDuckGo.

### What you need

- A 64-bit PC or laptop from around 2010 or newer (Intel Core i3/i5/i7, or AMD
  from 2011 on). Core 2 Duo and older won't work.
- 2 GB of RAM (4 GB is better).
- An 8 GB USB stick.
- Heads up: installing erases the computer's disk.

### How to try it

There's no ISO to download yet: it's bigger than the 2 GB GitHub allows per
file, and I'm still figuring out where to host it. For now you build it yourself
([how](docs/technical.md#build)). Then:

1. Write the ISO to a USB stick (Rufus, balenaEtcher or Ventoy).
2. Plug the computer into the TV and boot from the stick.
3. Try it as is. Liked it? Pick "Instalar o Otto TV neste computador" in the
   boot menu (the menus are in Portuguese for now).

### Status

Still early. So far it has only run on one machine: my Acer, hooked up to a
Samsung TV. If you try it on yours, open an issue and tell me how it went,
especially if something breaks.

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

Aí liguei o notebook na TV e coloquei Android TV nele. Funcionou, mais ou
menos: demorava muito pra ligar, o som saía no lugar errado, não tinha
controle, e metade das telas esperava um controle remoto em vez de um
notebook. O Otto TV sou eu arrumando tudo isso, uma coisa de cada vez.

No meu Acer (um i5 antigo com HD) ele liga em uns 40 segundos (antes eram
77) e a tela inicial roda lisinha.

### O que ele faz

- Transforma um PC ou notebook velho numa Android TV. Liga na TV pelo HDMI e
  pronto.
- Tela inicial própria: seus favoritos em cima, todos os apps a um clique.
- O celular vira controle. Escaneia o QR code da TV e pronto, sem instalar
  app nenhum (iPhone e Android).
- O som vai pra TV sozinho. Prefere os alto-falantes do notebook? Um clique.
- Dá pra apagar a tela do notebook enquanto você assiste na TV.
- Ele se atualiza sozinho: as versões novas aparecem no painel rápido (F2).
- Já vem com F-Droid, Aurora Store e DuckDuckGo.

### O que você precisa

- Um PC ou notebook 64 bits de 2010 pra cá (Intel Core i3/i5/i7, ou AMD de
  2011 em diante). Core 2 Duo e mais antigos não funcionam.
- 2 GB de RAM (4 GB é melhor).
- Um pendrive de 8 GB.
- Atenção: instalar apaga o disco do computador.

### Como testar

Ainda não tem ISO pra baixar: ela passa dos 2 GB que o GitHub aceita por
arquivo, e eu ainda tô vendo onde hospedar. Por enquanto você mesmo gera a
sua ([como](docs/technical.md#build)). Depois:

1. Grava a ISO num pendrive (Rufus, balenaEtcher ou Ventoy).
2. Liga o computador na TV e dá boot pelo pendrive.
3. Testa direto do pendrive. Gostou? Escolhe "Instalar o Otto TV neste
   computador" no menu de boot.

### Status

Ainda no começo. Até agora só rodou numa máquina: o meu Acer, ligado numa TV
Samsung. Se você testar no seu, abre uma issue e me conta como foi,
principalmente se algo quebrar.

### Mais detalhes

Como funciona, como gerar a ISO, opções de boot e o resto (em inglês):
[docs/technical.md](docs/technical.md).

O Otto TV é feito em cima do
[LineageOS TV x86](https://github.com/LineageOS-TV-x86), que por sua vez vem
do LineageOS, do [Bliss OS](https://blissos.org) e do Android-x86. Eu não
recompilo o Android: pego a imagem deles e coloco as minhas coisas por cima.
Os apps que vêm junto são do [F-Droid](https://f-droid.org).
