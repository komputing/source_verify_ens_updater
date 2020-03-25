package eth.verificat.ensupdater

import io.ipfs.kotlin.defaults.LocalIPFS
import okhttp3.OkHttpClient
import org.kethereum.crypto.createEthereumKeyPair
import org.kethereum.crypto.toAddress
import org.kethereum.crypto.toECKeyPair
import org.kethereum.eip137.model.ENSName
import org.kethereum.eip155.signViaEIP155
import org.kethereum.ens.ENS
import org.kethereum.erc1577.model.SuccessfulToContentHashResult
import org.kethereum.erc1577.model.SuccessfulToURIResult
import org.kethereum.erc1577.toContentHash
import org.kethereum.erc1577.toURI
import org.kethereum.extensions.transactions.calculateHash
import org.kethereum.extensions.transactions.encodeRLP
import org.kethereum.keystore.api.InitializingFileKeyStore
import org.kethereum.model.ChainId
import org.kethereum.model.PrivateKey
import org.kethereum.rpc.BaseEthereumRPC
import org.kethereum.rpc.ConsoleLoggingTransportWrapper
import org.kethereum.rpc.min3.MAINNET_BOOTNODES
import org.kethereum.rpc.min3.MIN3Transport
import org.kethereum.rpc.min3.getMin3RPC
import org.walleth.khex.hexToByteArray
import org.walleth.khex.toHexString
import java.io.File
import java.lang.Exception
import java.math.BigInteger
import java.math.BigInteger.*
import java.net.URI
import java.security.KeyPair

val ensDomain = "verificat.eth"

val rpc = getMin3RPC()
val ipfs = LocalIPFS()
val ens = ENS(rpc)

fun main(args: Array<String>) {

    if (args.size != 2) {
        error("Need 2 arguments - the private key (in hex) and the path with the repository")
    }
    val keyPair = PrivateKey(args.first().hexToByteArray()).toECKeyPair()
    val address = keyPair.toAddress()

    val repository = File(args.last())

    val probePath = File(repository, "contract")
    if (!probePath.exists()) {
        error("the path to the repository does not seem to be correct as $probePath was not found")
    }

    println("address: $address")

    val uri = retry { ens.getContentHash(ENSName(ensDomain)) }?.toURI()

    if (uri !is SuccessfulToURIResult) {
        error("could not resolve current content uri for $ensDomain")
    }

    println("current uri for $ensDomain: ${uri.uri}")
    val ipfsHash = try {
        ipfs.add.file(repository)
    } catch (e: Exception) {
        null
    }?.Hash ?: error("Cannot invoke ipfs add. Is the IPFS daemon running?")

    val newURI = "ipfs://$ipfsHash"

    println("local uri: $newURI")

    if (uri.uri == newURI) {
        println("both hashes are the same - nothing to do")
    } else {

        val contentHashResult = URI.create(newURI).toContentHash()

        if (contentHashResult !is SuccessfulToContentHashResult) {
            error("could not convert uri to content hash $contentHashResult")
        }

        val updateTx = retry {
            ens.getTransactionForSetContentHash(ENSName(ensDomain), contentHashResult.contentHash)
        } ?: error("Could not create update tx")

        val nonce = retry {
            rpc.getTransactionCount(address.hex)
        }

        val updateTxWithDetails = updateTx.copy(
            from = address,
            nonce = nonce,
            // we only want to spend 1GEWI - no updates if gas price s high
            gasPrice = BigInteger("1000000000"),
            gasLimit = ZERO,
            chain = valueOf(1),
            value = ZERO
        )

        val gas = retry {
            rpc.estimateGas(updateTxWithDetails)
        } ?: error("could not estimate gas")

        val txWithGasLimit = updateTxWithDetails.copy(gasLimit = gas * valueOf(2L))

        val signature = txWithGasLimit.signViaEIP155(keyPair, ChainId(1))

        val relayResult = rpc.sendRawTransaction(txWithGasLimit.encodeRLP(signature).toHexString())
        println("Relaying tx: $relayResult")

    }
}

