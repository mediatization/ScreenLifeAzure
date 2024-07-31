const { BlobClient } = require("@azure/storage-blob");
const fs = require("fs");

class Downloader {
    constructor() {
        this.queue = [];
        this.maxNumDownloads = 1000;
        this.downloading = false
        this.currentKey = "";
    }

    setCurrentKey(newKey)
    {
        this.currentKey = newKey;
    }

    addFilesToQueue(files) {
        this.queue = [...this.queue, ...files];
    }

    printQueue() {
        console.log(`${this.queue.length} items in queue`);
    }

    async startDownloading() {
        for (let i = 0; i < this.maxNumDownloads; i++) {
            this.downloadProcess();
        }
    }

    async downloadProcess() {
        if (this.downloading) {
            return;
        }
        this.downloading = true
        while (this.queue.length > 0) {
            const file = this.queue.shift()
            await ensureDownload(file, this.currentKey);
        }
        this.downloading = false
    }
}

const exists = async (destPath) => {
    return new Promise((resolve, reject) => {
        fs.stat(destPath, (err, stats) => {
            if (!err && stats.size > 0) {
                resolve(true);
            } else {
                resolve(false);
            }
        });
    });
};

// New with azure download
const ensureDownload = async (file, key) => {
    return new Promise((resolve, reject) => {
        fs.mkdir(
            `./encrypted/${key}`,
            { recursive: true },
            async () => {
                const path = `./encrypted/${key}/${file.name}`;
                fs.stat(path, (err, stats) => {
                    if (!err && stats.size > 0) {
                        resolve(true);
                    }
                });
                await file.downloadToFile(path),
                resolve(true);
            }
        );
    });
};

module.exports = { Downloader };
