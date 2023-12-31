package com.github.bunnyi.syncmatica.communication.exchange;

import com.github.bunnyi.syncmatica.ServerPlacement;
import com.github.bunnyi.syncmatica.SyncmaticaContext;
import com.github.bunnyi.syncmatica.communication.ExchangeTarget;
import com.github.bunnyi.syncmatica.communication.MessageType;
import com.github.bunnyi.syncmatica.communication.PacketType;
import com.github.bunnyi.syncmatica.util.Identifier;
import com.github.bunnyi.syncmatica.util.PacketByteBuf;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public class DownloadExchange extends Exchange {

    private final ServerPlacement toDownload;
    private final OutputStream outputStream;
    private final MessageDigest md5;
    private final File downloadFile;
    private int bytesSent;

    public DownloadExchange(ServerPlacement syncmatic, File downloadFile, ExchangeTarget partner, SyncmaticaContext context) throws IOException, NoSuchAlgorithmException {
        super(partner, context);
        OutputStream os = Files.newOutputStream(downloadFile.toPath()); //NOSONAR
        this.downloadFile = downloadFile;
        this.toDownload = syncmatic;
        this.md5 = MessageDigest.getInstance("MD5");
        this.outputStream = new DigestOutputStream(os, md5);
    }

    public boolean checkPacket(Identifier id, PacketByteBuf packetBuf) {
        if (id.equals(PacketType.SEND_LITEMATIC.identifier)
                || id.equals(PacketType.FINISHED_LITEMATIC.identifier)
                || id.equals(PacketType.CANCEL_LITEMATIC.identifier)) {
            return checkUUID(packetBuf, toDownload.getId());
        }
        return false;
    }

    public void handle(Identifier id, PacketByteBuf packetBuf) {
        packetBuf.readUuid(); // 跳过UUID
        if (id.equals(PacketType.SEND_LITEMATIC.identifier)) {
            int size = packetBuf.readInt();
            bytesSent += size;
            if (context.quotaService.isOverQuota(partner, bytesSent)) {
                close(true);
                context.communicationManager.sendMessage(
                        partner,
                        MessageType.ERROR,
                        "syncmatica.error.cancelled_transmit_exceed_quota"
                );
            }
            try {
                packetBuf.readBytes(outputStream, size);
            } catch (IOException e) {
                close(true);
                e.printStackTrace();
                return;
            }
            PacketByteBuf packetByteBuf = new PacketByteBuf();
            packetByteBuf.writeUuid(toDownload.getId());
            partner.sendPacket(PacketType.RECEIVED_LITEMATIC.identifier, packetByteBuf, context);
            return;
        }
        if (id.equals(PacketType.FINISHED_LITEMATIC.identifier)) {
            try {
                outputStream.flush();
            } catch (IOException e) {
                close(false);
                e.printStackTrace();
                return;
            }
            UUID downloadHash = UUID.nameUUIDFromBytes(md5.digest());
            if (downloadHash.equals(toDownload.getHash())) {
                succeed();
            } else {
                // no need to notify partner since exchange is closed on partner side
                close(false);
            }
            return;
        }
        if (id.equals(PacketType.CANCEL_LITEMATIC.identifier)) {
            close(false);
        }
    }

    public void init() {
        PacketByteBuf packetByteBuf = new PacketByteBuf();
        packetByteBuf.writeUuid(toDownload.getId());
        partner.sendPacket(PacketType.REQUEST_LITEMATIC.identifier, packetByteBuf, context);
    }

    @Override
    protected void onClose() {
        getManager().setDownloadState(toDownload, false);
        if (isSuccessful()) {
            context.quotaService.progressQuota(partner, bytesSent);
        }
        try {
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (!isSuccessful() && downloadFile.exists()) {
            downloadFile.delete();
        }
    }

    @Override
    public void sendCancelPacket() {
        PacketByteBuf packetByteBuf = new PacketByteBuf();
        packetByteBuf.writeUuid(toDownload.getId());
        partner.sendPacket(PacketType.CANCEL_LITEMATIC.identifier, packetByteBuf, context);
    }

    public ServerPlacement getPlacement() {
        return toDownload;
    }

}
