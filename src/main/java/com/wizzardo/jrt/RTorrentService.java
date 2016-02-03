package com.wizzardo.jrt;

import com.wizzardo.epoll.ByteBufferProvider;
import com.wizzardo.epoll.ByteBufferWrapper;
import com.wizzardo.http.framework.Holders;
import com.wizzardo.http.framework.di.Service;
import com.wizzardo.tools.collections.lazy.Lazy;
import com.wizzardo.tools.evaluation.Config;

import java.util.*;

/**
 * Created by wizzardo on 07.12.15.
 */
public class RTorrentService implements Service {

    private RTorrentClient client;
    protected Updater updater;
    AppWebSocketHandler appWebSocketHandler;

    public RTorrentService() {
        Config rtorrentConfig = Holders.getConfig().config("jrt").config("rtorrent");
        String host = rtorrentConfig.get("host", "localhost");
        int port = rtorrentConfig.get("port", 5000);
        client = new RTorrentClient(host, port);
        updater = new Updater(this);
        updater.start();
    }

    public List<TorrentInfo> list() {
        List<TorrentInfo> torrents = client.getTorrents();
        Collections.reverse(torrents);
        return torrents;
    }

    public Collection<TorrentEntry> entries(TorrentInfo ti) {
        return client.getEntries(ti);
    }

    public Collection<TorrentEntry> entries(String hash) {
        return client.getEntries(hash);
    }

    public void load(String torrent) {
        client.load(torrent);
    }

    public void load(String torrent, boolean autostart) {
        client.load(torrent, autostart);
    }

    public void start(String torrent) {
        client.start(torrent);
    }

    public void stop(String torrent) {
        client.stop(torrent);
    }

    public void delete(String torrent, boolean withData) {
        if (withData)
            client.removeWithData(torrent);
        else
            client.remove(torrent);
    }

    public void pauseUpdater() {
        updater.pause();
    }

    public void resumeUpdater() {
        updater.unpause();
    }

    protected static class Updater extends Thread implements ByteBufferProvider {

        volatile boolean pause = true;
        final ByteBufferWrapper buffer = new ByteBufferWrapper(1024 * 50);
        final RTorrentService rTorrentService;

        Map<String, TorrentInfo> torrents = new HashMap<>();


        public Updater(RTorrentService rTorrentService) {
            this.rTorrentService = rTorrentService;
            setDaemon(true);
        }

        @Override
        public void run() {
            while (true) {
                while (pause) {
                    synchronized (this) {
                        while (pause) {
                            try {
                                this.wait();
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                }

                try {
                    check();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }

        protected void check() {
            List<TorrentInfo> list = rTorrentService.list();
            outer:
            while (list.size() < torrents.size()) {
                for (String hash : torrents.keySet()) {
                    TorrentInfo torrent = Lazy.of(list).filter(it -> it.getHash().equals(hash)).first();
                    if (torrent == null) {
                        torrent = torrents.remove(hash);
                        rTorrentService.appWebSocketHandler.onRemove(torrent);
                        continue outer;
                    }
                }
            }
            for (TorrentInfo info : list) {
                TorrentInfo old = torrents.get(info.getHash());
                if (old != null && isUpdated(old, info)) {
                    rTorrentService.appWebSocketHandler.onUpdate(info);
                    torrents.put(info.getHash(), info);
                } else if (old == null) {
                    rTorrentService.appWebSocketHandler.onAdd(info);
                    torrents.put(info.getHash(), info);
                }
            }
        }

        protected boolean isUpdated(TorrentInfo o, TorrentInfo n) {
            if (o.getStatus() != n.getStatus())
                return true;
            if (o.getDownloaded() != n.getDownloaded())
                return true;
            if (o.getDownloadSpeed() != n.getDownloadSpeed())
                return true;
            if (o.getUploaded() != n.getUploaded())
                return true;
            if (o.getUploadSpeed() != n.getUploadSpeed())
                return true;
            if (o.getPeers() != n.getPeers())
                return true;
            if (o.getSeeds() != n.getSeeds())
                return true;
            if (o.getTotalPeers() != n.getTotalPeers())
                return true;
            if (o.getTotalSeeds() != n.getTotalSeeds())
                return true;

            return false;
        }

        public void pause() {
            pause = true;
            System.out.println("updater paused");
        }

        public void unpause() {
            synchronized (this) {
                pause = false;
                this.notifyAll();
            }
            System.out.println("updater unpaused");
        }

        @Override
        public ByteBufferWrapper getBuffer() {
            return buffer;
        }
    }
}
