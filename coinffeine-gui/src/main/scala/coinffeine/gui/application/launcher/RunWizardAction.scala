package coinffeine.gui.application.launcher

import java.io.File
import scala.util.Success

import org.bitcoinj.core.Wallet

import coinffeine.gui.setup.SetupWizard
import coinffeine.model.bitcoin.{KeyPair, Network}
import coinffeine.peer.config.ConfigProvider
import coinffeine.peer.config.user.LocalAppDataDir

class RunWizardAction(configProvider: ConfigProvider, network: Network) {

  def apply() = Success(if (mustRunWizard) { runSetupWizard() })

  private def mustRunWizard: Boolean = configProvider.userConfig.isEmpty

  private def runSetupWizard(): Unit = {
    val keys = new KeyPair()
    val address = keys.toAddress(network)
    val setupConfig = new SetupWizard(address.toString).run()

    configProvider.saveUserSettings(
      configProvider.bitcoinSettings().copy(walletFile = createWallet(keys)))

    setupConfig.okPayWalletAccess.foreach { access =>
      val okPaySettings = configProvider.okPaySettings()
      configProvider.saveUserSettings(okPaySettings.copy(
        userAccount = Some(access.walletId),
        seedToken = Some(access.seedToken)
      ))
    }
  }

  private def createWallet(keys: KeyPair): File = {
    val wallet = new Wallet(network)
    wallet.importKey(keys)
    val walletFile = LocalAppDataDir.getFile("user.wallet", ensureCreated = false).toFile
    wallet.saveToFile(walletFile)
    walletFile
  }
}
