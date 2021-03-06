package coinffeine.peer.market.orders.controller

import org.scalatest.Inside
import org.scalatest.mock.MockitoSugar

import coinffeine.common.test.UnitTest
import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.currency._
import coinffeine.model.exchange._
import coinffeine.model.market._
import coinffeine.model.network.PeerId
import coinffeine.model.payment.OkPayPaymentProcessor
import coinffeine.peer.amounts.DefaultAmountsComponent
import coinffeine.peer.exchange.protocol.MockExchangeProtocol
import coinffeine.protocol.messages.brokerage.OrderMatch

class OrderControllerTest extends UnitTest with Inside with MockitoSugar with SampleExchange {

  val initialOrder = Order(Bid, 10.BTC, Price(1.EUR))
  val orderMatch = OrderMatch(
    orderId = initialOrder.id,
    exchangeId = ExchangeId.random(),
    bitcoinAmount = Both(buyer = 10.BTC, seller = 10.0003.BTC),
    fiatAmount = Both(buyer = 10.EUR, seller = OkPayPaymentProcessor.amountMinusFee(10.EUR)),
    lockTime = 80L,
    counterpart = PeerId("counterpart")
  )

  "An order controller" should "start new exchanges" in new Fixture {
    order.shouldAcceptOrderMatch(orderMatch) shouldBe
      MatchAccepted(RequiredFunds(4.0202005.BTC, 10.EUR))
    val newExchange = order.acceptOrderMatch(orderMatch)
    newExchange.amounts shouldBe amountsCalculator.exchangeAmountsFor(orderMatch)
    newExchange.role shouldBe BuyerRole
    order.view.exchanges should have size 1
  }

  it should "notify order state changes" in new Fixture {
    order.becomeOffline()
    listener.lastStatus shouldBe OfflineOrder

    order.becomeInMarket()
    listener.lastStatus shouldBe InMarketOrder

    order.acceptOrderMatch(orderMatch)
    listener.lastStatus shouldBe InProgressOrder

    order.completeExchange(complete(order.view.exchanges.values.head))
    listener.lastStatus shouldBe CompletedOrder
  }

  it should "stop publishing orders upon cancellation" in new Fixture {
    order.cancel("not needed anymore")
    listener should not be 'inMarket
  }

  it should "notify successful termination" in new Fixture {
    order.acceptOrderMatch(orderMatch)
    order.completeExchange(complete(order.view.exchanges.values.head))
    listener.lastStatus shouldBe CompletedOrder
  }

  it should "notify termination upon cancellation" in new Fixture {
    val cancellationReason = "for the fun of it"
    order.cancel(cancellationReason)
    listener.lastStatus shouldBe CancelledOrder(cancellationReason)
  }

  it should "reject order matches during other exchange" in new Fixture {
    order.acceptOrderMatch(orderMatch)
    order.shouldAcceptOrderMatch(orderMatch.copy(exchangeId = ExchangeId("other"))) shouldBe
      MatchRejected("Exchange already in progress")
  }

  it should "recognize already accepted matches" in new Fixture {
    val exchangeInProgress = order.acceptOrderMatch(orderMatch)
    order.shouldAcceptOrderMatch(orderMatch) shouldBe MatchAlreadyAccepted(exchangeInProgress)
  }

  it should "reject order matches when order is finished" in new Fixture {
    order.cancel("finished")
    order.shouldAcceptOrderMatch(orderMatch) shouldBe MatchRejected("Order already finished")
  }

  it should "support partial matching" in new Fixture {
    val firstHalfMatch, secondHalfMatch = orderMatch.copy(
      exchangeId = ExchangeId.random(),
      bitcoinAmount = Both(buyer = 5.BTC, seller = 5.0003.BTC),
      fiatAmount = Both(buyer = 5.EUR, seller = OkPayPaymentProcessor.amountMinusFee(5.EUR))
    )
    order.view.amounts.pending shouldBe initialOrder.amount
    order.becomeInMarket()

    inside(order.shouldAcceptOrderMatch(firstHalfMatch)) { case MatchAccepted(_) => }
    order.acceptOrderMatch(firstHalfMatch)
    order.completeExchange(complete(order.view.exchanges.values.last))
    listener.lastStatus shouldBe OfflineOrder

    order.view.amounts.pending shouldBe (initialOrder.amount / 2)
    order.becomeInMarket()

    inside(order.shouldAcceptOrderMatch(secondHalfMatch)) { case MatchAccepted(_) => }
    order.acceptOrderMatch(secondHalfMatch)
    order.completeExchange(complete(order.view.exchanges.values.last))
    listener.lastStatus shouldBe CompletedOrder
    listener should not be 'inMarket
  }

  trait Fixture extends DefaultAmountsComponent {
    val listener = new MockOrderControllerListener[Euro.type]
    val order = new OrderController[Euro.type](
      amountsCalculator, CoinffeineUnitTestNetwork, initialOrder)
    order.addListener(listener)

    def complete(exchange: AnyStateExchange[Euro.type]): SuccessfulExchange[Euro.type] = {
      val completedState = Exchange.Successful[Euro.type](
        participants.buyer,
        participants.seller,
        MockExchangeProtocol.DummyDeposits)(exchange.amounts)
      exchange.copy(state = completedState)
    }
  }
}
