(channelName) => {
    const channel = new BroadcastChannel(channelName);
    let urls = [];
    const lengths = new Map();
    const images = [];
    const delivered = new Set();

    const match = () => {
        for (let i = images.length - 1; i >= 0; i--) {
            // The reader currently uses AES-GCM: nonce + authentication tag = 28 bytes.
            // Never guess if two fetches could match: that silently swaps manga panels.
            const candidates = [...lengths].filter(([, length]) => {
                const overhead = length - images[i].size;
                return overhead === 28 || overhead === 0;
            });
            if (candidates.length !== 1) continue;
            const [index] = candidates[0];
            lengths.delete(index);
            delivered.add(index);
            channel.postMessage({ index, image: images[i] });
            images.splice(i, 1);
        }
        if (images.length > 16) {
            channel.postMessage({ error: "Reader images could not be matched safely" });
            images.length = 0;
        }
    };

    self.addEventListener("message", (event) => {
        const data = event.data;
        if (data && data.type === "store" && Array.isArray(data.urls)) {
            urls = data.urls;
            lengths.clear();
            images.length = 0;
            delivered.clear();
            channel.postMessage({ count: urls.length });
        }
    }, true);

    Response.prototype.arrayBuffer = new Proxy(Response.prototype.arrayBuffer, {
        apply(target, response, args) {
            const result = Reflect.apply(target, response, args);
            const index = urls.indexOf(response.url);
            if (index >= 0 && !delivered.has(index)) {
                result.then((buffer) => {
                    if (delivered.has(index)) return;
                    lengths.set(index, buffer.byteLength);
                    match();
                }, () => {});
            }
            return result;
        },
    });

    self.createImageBitmap = new Proxy(self.createImageBitmap, {
        apply(target, thisArg, args) {
            if (args[0] instanceof Blob) {
                images.push(args[0]);
                match();
            }
            return Reflect.apply(target, thisArg, args);
        },
    });
}
