'use strict';
// Descobre o ID do canal do Telegram. Antes de rodar:
//  1) crie o bot no @BotFather e cole o token em segredos.json (telegram.botToken)
//  2) adicione o bot como ADMIN do canal
//  3) poste qualquer mensagem no canal
// Depois: node find-channel.js   -> copie o id (ex: -1001234567890) para telegram.chatId
const { getUpdates } = require('./telegram');

(async () => {
  const cfg = require('./config')();
  const token = cfg.telegram.token;
  if (!token) { console.log('Cole primeiro o token do bot em segredos.json (telegram.botToken).'); return; }
  const chats = await getUpdates(token);
  if (!chats.length) {
    console.log('Nenhum chat visto ainda. Confira: bot e admin do canal? Voce postou algo la?');
    return;
  }
  console.log('Chats que o bot ja viu:');
  for (const c of chats) console.log(`  ${c.type.padEnd(10)} id=${c.id}  ${c.title}`);
  const ch = chats.find((c) => c.type === 'channel');
  if (ch) console.log(`\n>> Use este no config.json:  "chatId": "${ch.id}"`);
})().catch((e) => console.error('erro:', e.message));
