package com.platinumcoin.outbox;

import com.platinumcoin.outbox.infra.config.Ambiente;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemoryDestFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * O outro lado do fio, visto pelo teste: o diretório do parceiro consultado
 * por uma conexão SSH própria.
 *
 * <p>Existe para que a asserção seja sobre o que <b>o parceiro tem</b>, e não
 * sobre o que o nosso canal diz ter enviado. Um teste que conferisse a
 * transmissão pelo mesmo objeto que a executou não provaria nada sobre o
 * arquivo do outro lado.
 */
final class DiretorioDoParceiro {

    private final Ambiente.ServidorSftp servidor;

    DiretorioDoParceiro(Ambiente.ServidorSftp servidor) {
        this.servidor = servidor;
    }

    /** Os nomes dos arquivos do diretório, em ordem estável. */
    List<String> listar(String diretorio) {
        return conectado(sftp -> sftp.ls(diretorio).stream()
                .map(RemoteResourceInfo::getName)
                .sorted()
                .toList());
    }

    byte[] baixar(String caminho) {
        return conectado(sftp -> {
            ByteArrayOutputStream recebido = new ByteArrayOutputStream();
            sftp.get(caminho, new InMemoryDestFile() {
                @Override
                public OutputStream getOutputStream() {
                    return recebido;
                }

                @Override
                public OutputStream getOutputStream(boolean append) {
                    return recebido;
                }

                @Override
                public long getLength() {
                    return recebido.size();
                }
            });
            return recebido.toByteArray();
        });
    }

    /**
     * Esvazia o diretório entre testes.
     *
     * <p>É o equivalente remoto do {@code TRUNCATE}: a contagem de arquivos só
     * afirma alguma coisa se o que está lá foi posto por este teste.
     */
    void limpar(String diretorio) {
        conectado(sftp -> {
            for (RemoteResourceInfo arquivo : sftp.ls(diretorio)) {
                sftp.rm(arquivo.getPath());
            }
            return null;
        });
    }

    private <T> T conectado(Operacao<T> operacao) {
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.connect(servidor.host(), servidor.porta());
            ssh.authPassword(servidor.usuario(), servidor.senha());
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                return operacao.executar(sftp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("falha ao inspecionar o diretório do parceiro", e);
        }
    }

    private interface Operacao<T> {
        T executar(SFTPClient sftp) throws IOException;
    }
}
