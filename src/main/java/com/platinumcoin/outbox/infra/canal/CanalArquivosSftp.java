package com.platinumcoin.outbox.infra.canal;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.exception.FalhaDePublicacao;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.infra.config.Ambiente;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
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
import java.util.Optional;

/**
 * {@link CanalArquivos} sobre SSH de verdade.
 *
 * <p>Uma conexão por operação, aberta e fechada aqui. Não é economia de
 * código: uma sessão SSH viva entre execuções seria estado a manter — e estes
 * use cases são jobs disparados por agendamento, não serviços com conexão
 * quente.
 *
 * <p>O {@code put} escreve direto no caminho final, sem arquivo temporário e
 * sem rename. É o comportamento que o parceiro real tem, e o step-09 lida com a
 * consequência dele do lado de cá: um arquivo pode estar visível pela metade, e
 * é o trailer — não o transporte — que diz se ele está completo.
 *
 * <p>Nenhum método apaga nada. O diretório é do parceiro, e a coleta reconhece
 * o que já processou pelos bytes, não pelo desaparecimento do arquivo.
 */
public final class CanalArquivosSftp implements CanalArquivos {

    /**
     * O diretório combinado com o parceiro. Não é configuração: é contrato,
     * como as posições dos campos da remessa.
     */
    private static final String DIRETORIO_REMESSA = "/remessa";

    private final Ambiente.ServidorSftp servidor;

    public CanalArquivosSftp(Ambiente.ServidorSftp servidor) {
        this.servidor = servidor;
    }

    @Override
    public void enviar(String nome, byte[] conteudo) {
        try {
            conectado(sftp -> {
                sftp.put(new Artefato(nome, conteudo), DIRETORIO_REMESSA + "/" + nome);
                return null;
            });
        } catch (IOException e) {
            // Traduz antes de atravessar a porta, como o publicador do SQS: o
            // use case não pode depender de um tipo de biblioteca SSH para
            // saber que a entrega falhou — e, como toda entrega externa, falhar
            // aqui não diz se o arquivo chegou.
            throw new FalhaDePublicacao("falha ao transmitir " + nome + " ao parceiro", e);
        }
    }

    @Override
    public List<String> listar(String diretorio) {
        try {
            return conectado(sftp -> sftp.ls(diretorio).stream()
                    .filter(RemoteResourceInfo::isRegularFile)
                    .map(RemoteResourceInfo::getPath)
                    .sorted()
                    .toList());
        } catch (IOException e) {
            throw new FalhaDePersistencia("falha ao listar " + diretorio + " no parceiro", e);
        }
    }

    @Override
    public Optional<CanalArquivos.Atributos> atributos(String caminho) {
        try {
            return conectado(sftp -> {
                // statExistence e não stat: o arquivo que sumiu entre a listagem
                // e esta pergunta é resposta, não erro — ver a porta.
                FileAttributes atributos = sftp.statExistence(caminho);
                return Optional.ofNullable(atributos).map(
                        lidos -> new CanalArquivos.Atributos(lidos.getSize(), lidos.getMtime()));
            });
        } catch (IOException e) {
            throw new FalhaDePersistencia("falha ao consultar " + caminho + " no parceiro", e);
        }
    }

    @Override
    public byte[] baixar(String caminho) {
        try {
            return conectado(sftp -> {
                ByteArrayOutputStream recebido = new ByteArrayOutputStream();
                sftp.get(caminho, new Recebido(recebido));
                return recebido.toByteArray();
            });
        } catch (IOException e) {
            throw new FalhaDePersistencia("falha ao baixar " + caminho + " do parceiro", e);
        }
    }

    /**
     * Uma conexão por operação, aberta e fechada aqui.
     *
     * <p>Cada passada da coleta abre algumas — uma para listar, duas por arquivo
     * para os atributos, uma para baixar. É caro por design: o alternativo seria
     * uma sessão viva entre execuções, e isso é estado a manter num job que roda
     * por agendamento.
     */
    private <T> T conectado(Operacao<T> operacao) throws IOException {
        try (SSHClient ssh = new SSHClient()) {
            // Contra um parceiro de verdade isto seria a known_hosts do
            // processo. Aqui o outro lado é um container que nasce com uma
            // chave nova a cada execução, e verificar o quê não existe seria
            // teatro de segurança.
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.connect(servidor.host(), servidor.porta());
            ssh.authPassword(servidor.usuario(), servidor.senha());

            try (SFTPClient sftp = ssh.newSFTPClient()) {
                return operacao.executar(sftp);
            }
        }
    }

    private interface Operacao<T> {
        T executar(SFTPClient sftp) throws IOException;
    }

    /** Os bytes que chegam, acumulados em memória — arquivo de retorno é pequeno. */
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
