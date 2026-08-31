package com.platinumcoin.outbox.infra.persistence;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.ChaveArtefato;
import com.platinumcoin.outbox.domain.port.ArmazenamentoArtefato;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;

/**
 * {@link ArmazenamentoArtefato} sobre um bucket S3.
 *
 * <p>Não há {@code delete}: a porta não oferece e esta classe não inventa. O
 * {@code put} de uma chave que já existe sobrescreve — é o comportamento
 * padrão do S3, e é exatamente o que o desenho pede, porque a chave é
 * determinística e os bytes de uma segunda geração são os mesmos.
 * <br>DECISÃO: artefato durável entre geração e transmissão — ver ADR-0003
 */
public final class ArmazenamentoArtefatoS3 implements ArmazenamentoArtefato {

    private final S3Client s3;
    private final String bucket;

    public ArmazenamentoArtefatoS3(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(ChaveArtefato chave, byte[] conteudo) {
        try {
            s3.putObject(pedido -> pedido.bucket(bucket).key(chave.valor()),
                    RequestBody.fromBytes(conteudo));
        } catch (SdkException e) {
            // Traduz antes de atravessar a porta: o use case não pode depender
            // de um tipo do AWS SDK para saber que a gravação falhou.
            throw new FalhaDePersistencia("falha ao gravar o artefato " + chave, e);
        }
    }

    @Override
    public byte[] get(ChaveArtefato chave) {
        try (ResponseInputStream<GetObjectResponse> objeto =
                     s3.getObject(pedido -> pedido.bucket(bucket).key(chave.valor()))) {
            return objeto.readAllBytes();
        } catch (NoSuchKeyException e) {
            throw new FalhaDePersistencia("artefato inexistente: " + chave, e);
        } catch (SdkException | IOException e) {
            throw new FalhaDePersistencia("falha ao ler o artefato " + chave, e);
        }
    }

    @Override
    public boolean existe(ChaveArtefato chave) {
        try {
            s3.headObject(pedido -> pedido.bucket(bucket).key(chave.valor()));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            // "Não deu para perguntar" não é "não existe": responder false aqui
            // faria uma indisponibilidade do S3 parecer um artefato que sumiu.
            throw new FalhaDePersistencia("falha ao consultar o artefato " + chave, e);
        }
    }
}
