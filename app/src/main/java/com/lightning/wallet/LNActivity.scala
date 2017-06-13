package com.lightning.wallet

import com.lightning.wallet.R.string._
import com.lightning.wallet.Utils._
import android.content.DialogInterface.BUTTON_POSITIVE
import com.lightning.wallet.lncloud.ImplicitConversions.string2Ops
import com.lightning.wallet.ln.Tools.{none, runAnd, wrap}
import android.widget._
import android.view.{Menu, MenuItem, View}
import org.ndeftools.Message
import org.ndeftools.util.activity.NfcReaderActivity
import android.os.Bundle
import Utils.app
import android.support.v4.view.MenuItemCompat
import android.support.v7.widget.SearchView.OnQueryTextListener
import android.webkit.URLUtil
import com.lightning.wallet.ln.MSat._
import com.lightning.wallet.ln._
import com.lightning.wallet.lncloud.{PrivateData, PrivateDataSaver}
import org.bitcoinj.core.Address
import org.bitcoinj.uri.BitcoinURI

import scala.util.{Failure, Success}


trait SearchBar { me =>
  import android.support.v7.widget.SearchView
  protected[this] var searchItem: MenuItem = _
  protected[this] var search: SearchView = _

  private[this] val lst = new OnQueryTextListener {
    def onQueryTextSubmit(queryText: String) = true
    def onQueryTextChange(queryText: String) =
      runAnd(true)(me react queryText)
  }

  def setupSearch(menu: Menu) = {
    searchItem = menu findItem R.id.action_search
    val view = MenuItemCompat getActionView searchItem
    search = view.asInstanceOf[SearchView]
    search setOnQueryTextListener lst
  }

  def react(query: String)
  def mkBundle(args:(String, String)*) = new Bundle match { case bundle =>
    for (Tuple2(key, value) <- args) bundle.putString(key, value)
    bundle
  }
}

class LNActivity extends NfcReaderActivity
with ToolbarActivity with HumanTimeDisplay
with ListUpdater with SearchBar { me =>

  lazy val fab = findViewById(R.id.fab).asInstanceOf[com.github.clans.fab.FloatingActionMenu]
  lazy val lnItemsList = findViewById(R.id.lnItemsList).asInstanceOf[ListView]
  lazy val lnTitle = getString(ln_title)

  def react(query: String) = println(query)
  def notifySubTitle(subtitle: String, infoType: Int) = {
    add(subtitle, infoType).timer.schedule(me del infoType, 25000)
    me runOnUiThread ui
  }

  // Initialize this activity, method is run once
  override def onCreate(savedState: Bundle) =
  {
    super.onCreate(savedState)
    wrap(initToolbar)(me setContentView R.layout.activity_ln)
    add(me getString ln_notify_working, Informer.LNSTATE).ui.run
    setDetecting(true)

    app.kit.wallet addCoinsSentEventListener txTracker
    app.kit.wallet addCoinsReceivedEventListener txTracker
    app.kit.wallet addTransactionConfidenceEventListener txTracker
    app.kit.peerGroup addBlocksDownloadedEventListener new CatchTracker
  }

  override def onDestroy = wrap(super.onDestroy) {
    app.kit.wallet removeCoinsSentEventListener txTracker
    app.kit.wallet removeCoinsReceivedEventListener txTracker
    app.kit.wallet removeTransactionConfidenceEventListener txTracker
    stopDetecting
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    getMenuInflater.inflate(R.menu.ln_normal_ops, menu)
    setupSearch(menu)
    true
  }

  override def onOptionsItemSelected(m: MenuItem) = runAnd(true) {
    if (m.getItemId == R.id.actionSetBackupServer) new SetBackupServer
    else if (m.getItemId == R.id.actionCloseChannel) closeChannel
  }

  // Data reading

  override def onResume: Unit =
    wrap(super.onResume)(checkTransData)

  def readNdefMessage(msg: Message) = try {
    val asText = readFirstTextNdefMessage(msg)
    app.TransData recordValue asText
    checkTransData

  } catch { case _: Throwable =>
    // Could not process a message
    app toast nfc_error
  }

  def onNfcStateEnabled = none
  def onNfcStateDisabled = none
  def onNfcFeatureNotFound = none
  def onNfcStateChange(ok: Boolean) = none
  def readNonNdefMessage = app toast nfc_error
  def readEmptyNdefMessage = app toast nfc_error

  // Working with transitional data
  def checkTransData = app.TransData.value match {
    case uri: BitcoinURI => me goTo classOf[BtcActivity]
    case adr: Address => me goTo classOf[BtcActivity]

    case invoice: Invoice =>
      me displayInvoice invoice
      app.TransData.value = null

    case unusable =>
      Tools log s"Unusable $unusable"
      app.TransData.value = null
  }

  // Reactions to menu
  def goBitcoin(top: View) = {
    me goTo classOf[BtcActivity]
    fab close true
  }

  def goQR(top: View) = {
    me goTo classOf[ScanActivity]
    fab close true
  }

  def makePaymentRequest = {
    val humanCap = sumIn format withSign(LNParams.maxHtlcValue)
    val title = getString(ln_receive_max_amount).format(humanCap).html
    val content = getLayoutInflater.inflate(R.layout.frag_input_send_ln, null, false)
    val alert = mkForm(negPosBld(dialog_cancel, dialog_next), title, content)
    val rateManager = new RateManager(content)

    def attempt = rateManager.result match {
      case Failure(_) => app toast dialog_sum_empty
      case Success(ms) => println(ms)
    }

    val ok = alert getButton BUTTON_POSITIVE
    ok setOnClickListener onButtonTap(attempt)
  }

  def goReceive(top: View) = {
    me delayUI makePaymentRequest
    fab close true
  }

  private def displayInvoice(invoice: Invoice) = {
    val humanKey = humanPubkey(invoice.nodeId.toString)
    val info = invoice.message getOrElse getString(ln_no_description)
    val humanSum = humanFiat(sumOut format withSign(invoice.sum), invoice.sum)
    val title = getString(ln_payment_title).format(info, humanKey, humanSum)
    mkForm(negPosBld(dialog_cancel, dialog_pay), title.html, null)
  }

  class SetBackupServer { self =>
    val (view, field) = str2Tuple(LNParams.cloudPrivateKey.publicKey.toString)
    val dialog = mkChoiceDialog(proceed, none, dialog_next, dialog_cancel)
    val alert = mkForm(dialog, getString(ln_backup_key).html, view)
    field setTextIsSelectable true

    def proceed: Unit = rm(alert) {
      val (view1, field1) = generatePasswordPromptView(inpType = textType, txt = ln_backup_ip)
      val dialog = mkChoiceDialog(trySave(field1.getText.toString), none, dialog_ok, dialog_cancel)
      PrivateDataSaver.tryGetObject.foreach(field1 setText _.url)
      mkForm(dialog, me getString ln_backup, view1)
    }

    def trySave(url: String) =
      if (url.isEmpty) PrivateDataSaver.remove
      else if (URLUtil isValidUrl url) self save PrivateData(Nil, url)
      else mkForm(me negBld dialog_ok, null, me getString ln_backup_url_error)

    def save(data: PrivateData) = {
      PrivateDataSaver saveObject data
      LNParams.cloud = LNParams.currentLNCloud
      app toast ln_backup_success
    }

    def onError(error: Throwable): Unit = error.getMessage match {
      case "keynotfound" => mkForm(me negBld dialog_ok, null, me getString ln_backup_key_error)
      case "siginvalid" => mkForm(me negBld dialog_ok, null, me getString ln_backup_sig_error)
      case _ => mkForm(me negBld dialog_ok, null, me getString ln_backup_net_error)
    }
  }

  def closeChannel = passPlus(me getString ln_close) { pass =>
    println(pass)
  }
}