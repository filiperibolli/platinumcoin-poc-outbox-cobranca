package com.platinumcoin.outbox.infra.canal;

import com.platinumcoin.outbox.domain.exception.FalhaDePublicacao;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.infra.config.Ambiente;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * {@link CanalArquivos} sobre SSH de verdade.
 *
 * <p>Uma conexão por transmissão, aberta e fechada aqui. Não é economia de
 * código: uma sessão SSH viva entre execuções seria estado a manter — e este
 * use case é um job disparado por agendamento, não um serviço com conexão
 * quente.
 *
 * <p>O {@code put} escreve direto no caminho final, sem arquivo temporário e
 * sem rename. É o comportamento que o parceiro real tem, e o step-09 existe
 * para lidar com a consequência dele do lado de cá: um arquivo pode estar
 * visível pela metade, e é o trailer — não o transporte — que diz se ele está
 * completo.
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
        try (SSHClient ssh = new SSHClient()) {
            // Contra um parceiro de verdade isto seria a known_hosts do
            // processo. Aqui o outro lado é um container que nasce com uma
            // chave nova a cada execução, e verificar o quê não existe seria
            // teatro de segurança.
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.connect(servidor.host(), servidor.porta());
            ssh.authPassword(servidor.usuario(), servidor.senha());

            try (SFTPClient sftp = ssh.newSFTPClient()) {
                sftp.put(new Artefato(nome, conteudo), DIRETORIO_REMESSA + "/" + nome);
            }
        } catch (IOException e) {
            // Traduz antes de atravessar a porta, como o publicador do SQS: o
            // use case não pode depender de um tipo de biblioteca SSH para
            // saber que a entrega falhou — e, como toda entrega externa, falhar
            // aqui não diz se o arquivo chegou.
            throw new FalhaDePublicacao("falha ao transmitir " + nome + " ao parceiro", e);
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
