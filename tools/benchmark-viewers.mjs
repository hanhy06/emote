import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { setTimeout as wait } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const [viewerCount, port = '25565', clientPath] = process.argv.slice(2);
const players = Number(viewerCount);
if (!/^\d+$/.test(viewerCount ?? '') || !Number.isSafeInteger(players) || players < 1 || players > 100
    || !/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535 || process.argv.length > 5) {
    console.error('Usage: node tools/benchmark-viewers.mjs <50|100> [port] [mcclient executable]');
    process.exit(2);
}

const localClient = fileURLToPath(new URL('../.codex/network-benchmark/observer/bin/mcclient.exe', import.meta.url));
const executable = clientPath ?? (existsSync(localClient) ? localClient : 'mcclient');
const bots = [];
let readyCount = 0;
let stopping = false;

function stopBots(exitCode) {
    stopping = true;
    process.exitCode = exitCode;
    for (const bot of bots) {
        if (bot.process.exitCode === null && bot.process.signalCode === null) bot.process.kill();
    }
}

process.on('SIGINT', () => stopBots(0));
process.on('SIGTERM', () => stopBots(0));

for (let index = 1; index <= players && !stopping; index++) {
    const name = `Viewer${String(index).padStart(3, '0')}`;
    const child = spawn(executable, [`127.0.0.1:${port}`, name, '', '300', '--protocol', '777'], {
        stdio: ['ignore', 'ignore', 'pipe'],
        windowsHide: true,
    });
    const bot = { process: child, lastLine: '' };
    bots.push(bot);
    createInterface({ input: child.stderr }).on('line', line => {
        bot.lastLine = line;
        if (line.includes('sending 0 command(s), listening for')) {
            readyCount++;
            if (readyCount === players) {
                console.log(`${players} viewers ready. Run emote benchmark ${players} in the server console.`);
            }
        }
    });
    child.on('error', error => {
        if (stopping) return;
        console.error(`${name}: ${error.message}`);
        stopBots(1);
    });
    child.on('close', code => {
        if (stopping || code === 0) return;
        console.error(`${name}: ${bot.lastLine || `mcclient exited with code ${code}`}`);
        stopBots(1);
    });
    await wait(75);
}
