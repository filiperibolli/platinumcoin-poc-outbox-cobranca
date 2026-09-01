package com.platinumcoin.ciclo.simulador;

import com.platinumcoin.ciclo.infra.config.Ambiente;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemoryDestFile;
import net.schmizz.sshj.xfer.InMemorySourceFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * O diretório do parceiro, visto <b>por ele</b>: onde a remessa chega e onde o
 * retorno é deixado.
 *
 * <p>Não é {@code CanalArquivosSftp}, e a duplicação da conexão SSH é o preço
 * de uma fronteira. Aquele objeto é o <b>nosso</b> lado do fio: o
 * {@code enviar} dele escreve em {@code /remessa} porque é para lá que o nosso
 * sistema transmite. Reaproveitá-lo aqui obrigaria a porta do domínio a receber
 * um caminho de destino para servir ao simulador — e a primeira pergunta de
 * quem lesse {@code CanalArquivos} passaria a ser por que ela sabe escrever no
 * diretório de retorno.
 *
 * <p>Escreve direto no caminho final, sem temporário e sem rename, como o
 * parceiro real faz. É essa ausência de atomicidade que dá sentido à
 * quiescência e ao trailer do step-09.
 */
public final class DiscoDoParceiro {

    private final Ambiente.ServidorSftp servidor;

    public DiscoDoParceiro(Ambiente.ServidorSftp servidor) {
        this.servidor = servidor;
    }

    /** Os caminhos completos dos arquivos do diretório, em ordem estável. */
    public List<String> listar(String diretorio) {
        return conectado(sftp -> sftp.ls(diretorio).stream()
                .filter(RemoteResourceInfo::isRegularFile)
                .map(RemoteResourceInfo::getPath)
                .sorted()
                .toList());
    }

    public byte[] ler(String caminho) {
        return conectado(sftp -> {
            ByteArrayOutputStream lido = new ByteArrayOutputStream();
            sftp.get(caminho, new Recebido(lido));
            return lido.toByteArray();
        });
    }

    /** Deixa o conteúdo no caminho, sobrescrevendo o que houver lá. */
    public void escrever(String caminho, byte[] conteudo) {
        conectado(sftp -> {
            sftp.put(new Artefato(caminho, conteudo), caminho);
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
            // IllegalStateException, e não FalhaDePersistencia: as exceções do
            // domínio descrevem falhas do nosso sistema, e o simulador não é o
            // nosso sistema. Um parceiro que não consegue escrever o retorno é
            // um problema do ambiente da demonstração.
            throw new IllegalStateException("o parceiro não conseguiu acessar o disco dele", e);
        }
    }

    private interface Operacao<T> {
        T executar(SFTPClient sftp) throws IOException;
    }

    /** Os bytes que chegam, acumulados em memória — arquivo de ciclo é pequeno. */
    private static final class Recebido extends InMemoryDestFile {

        private final ByteArrayOutputStream destino;

        private Recebido(ByteArrayOutputStream destino) {
            this.destino = destino;
        }

        @Override
        public OutputStream getOutputStream() {
            return destino;
        }

        @Override
        public OutputStream getOutputStream(boolean append) {
            return destino;
        }

        @Override
        public long getLength() {
            return destino.size();
        }
    }

    /** Os bytes que já estão em memória, no formato que o sshj sabe enviar. */
    private static final class Artefato extends InMemorySourceFile {

        private final String nome;
        private final byte[] conteudo;

        private Artefato(String nome, byte[] conteudo) {
            this.nome = nome;
            this.conteudo = conteudo;
        }

        @Override
        public String getName() {
            return nome;
        }

        @Override
        public long getLength() {
            return conteudo.length;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(conteudo);
        }
    }
}
