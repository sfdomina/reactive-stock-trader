package com.redelastic.stockbroker.wireTransfer.impl.transfer;

import akka.Done;
import akka.japi.Pair;
import akka.stream.Attributes;
import akka.stream.javadsl.Flow;
import com.lightbend.lagom.javadsl.persistence.AggregateEventTag;
import com.lightbend.lagom.javadsl.persistence.Offset;
import com.lightbend.lagom.javadsl.persistence.ReadSideProcessor;
import com.redelastic.stocktrader.portfolio.api.FundsTransfer;
import com.redelastic.stocktrader.portfolio.api.PortfolioService;
import com.redelastic.stocktrader.wiretransfer.api.Account;
import lombok.extern.log4j.Log4j;
import lombok.val;
import org.pcollections.PSequence;

import javax.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Saga processor for transfers, this read side processes handles persisting progression of the saga through
 * the corresponding TransferEntities, and executing the steps of the saga.
 *
 * The transfer saga requires:
 * 1. retrieving the funds from the source account
 * 2. sending the funds to the destination account
 *
 * If the second step fails the funds should be refunded.
 */
@Log4j
public class TransferProcessor extends ReadSideProcessor<TransferEvent> {

    private final PortfolioService portfolioService;
    private final TransferRepository transferRepository;

    @Inject
    TransferProcessor(PortfolioService portfolioService,
                      TransferRepository transferRepository) {
        this.portfolioService = portfolioService;
        this.transferRepository = transferRepository;
    }


    @Override
    public ReadSideHandler<TransferEvent> buildHandler() {
        return new HandleEvent();
    }

    @Override
    public PSequence<AggregateEventTag<TransferEvent>> aggregateTags() {
        return TransferEvent.TAG.allTags();
    }

    class HandleEvent extends ReadSideHandler<TransferEvent> {

        @Override
        public Flow<Pair<TransferEvent, Offset>, Done, ?> handle() {
            return Flow.<Pair<TransferEvent, Offset>>create()
                    .log("transferEvent")
                    .withAttributes(
                            Attributes.createLogLevels(
                                    Attributes.logLevelInfo(),
                                    Attributes.logLevelInfo(),
                                    Attributes.logLevelInfo()
                            )
                    )
                    .mapAsync(1, e ->
                            e.first().visit(new TransferEventVisitor()));
        }

    }

    class TransferEventVisitor implements TransferEvent.Visitor<CompletionStage<Done>> {
        @Override
        public CompletionStage<Done> visit(TransferEvent.TransferInitiated transferInitiated) {
            val transferEntity = transferRepository.get(transferInitiated.getTransferId());
            if (transferInitiated.getSource() instanceof Account.Portfolio) {
                val transfer = FundsTransfer.FundsWithdrawn.builder()
                        .transferId(transferInitiated.getTransferId())
                        .funds(transferInitiated.getAmount())
                        .build();
                val portfolioId = ((Account.Portfolio) transferInitiated.getSource()).getPortfolioId();
                return portfolioService
                        .processTransfer(portfolioId)
                        .invoke(transfer)
                        .thenApply(done -> transferEntity.ask(TransferCommand.RequestFundsSucessful.INSTANCE))
                        .exceptionally(ex -> transferEntity.ask(TransferCommand.RequestFundsFailed.INSTANCE))
                        .thenCompose(Function.identity());
            } else {
                // Any other sort of accounts are out of scope, this means they will freely accept and transfer money.
                // You don't actually want sources of free money in a production system.
                return transferEntity
                        .ask(TransferCommand.RequestFundsSucessful.INSTANCE);
            }
        }

        @Override
        public CompletionStage<Done> visit(TransferEvent.FundsRetrieved fundsRetrieved) {
            return transferRepository
                    .get(fundsRetrieved.getTransferId())
                    .ask(TransferCommand.SendFunds.INSTANCE);
        }

        @Override
        public CompletionStage<Done> visit(TransferEvent.CouldNotSecureFunds couldNotSecureFunds) {
            // Saga failed, but nothing to compensate for
            return transferRepository
                    .get(couldNotSecureFunds.getTransferId())
                    .ask(TransferCommand.SendRefund.INSTANCE);
        }

        @Override
        public CompletionStage<Done> visit(TransferEvent.FundsSent fundsSent) {
            val transferEntity = transferRepository.get(fundsSent.getTransferId());
            if (fundsSent.getSource() instanceof Account.Portfolio) {

                val transfer = FundsTransfer.FundsDeposited.builder()
                        .transferId(fundsSent.getTransferId())
                        .funds(fundsSent.getAmount())
                        .build();
                val portfolioId = ((Account.Portfolio) fundsSent.getSource()).getPortfolioId();

                return portfolioService
                        .processTransfer(portfolioId)
                        .invoke(transfer)
                        .thenApply(done ->
                                transferEntity.ask(TransferCommand.SendFundsSuccessful.INSTANCE))
                        .exceptionally(ex ->
                                transferEntity.ask(TransferCommand.SendFundsFailed.INSTANCE))
                        .thenCompose(Function.identity());
            } else {
                // As above, any unimplemented account type just freely accepts transfers
                return transferEntity
                        .ask(TransferCommand.SendFundsSuccessful.INSTANCE);
            }
        }

        @Override
        public CompletionStage<Done> visit(TransferEvent.DeliveryFailed deliveryFailed) {
            if (deliveryFailed.getSource() instanceof Account.Portfolio) {
                val portfolioId = ((Account.Portfolio) deliveryFailed.getSource()).getPortfolioId();
                val refund = FundsTransfer.Refund.builder()
                        .transferId(deliveryFailed.getTransferId())
                        .funds(deliveryFailed.getAmount())
                        .build();
                return portfolioService
                        .processTransfer(portfolioId)
                        .invoke(refund);
            } else {
                return CompletableFuture.completedFuture(Done.getInstance());
            }
        }

        @Override
        public CompletionStage<Done> visit(TransferEvent.RefundSent refundSent) {
            // Saga is completed after refunding source
            return CompletableFuture.completedFuture(Done.getInstance());
        }

        @Override
        public CompletionStage<Done> visit(TransferEvent.DeliveryConfirm deliveryConfirm) {
            // Saga is completed successfully
            return CompletableFuture.completedFuture(Done.getInstance());
        }

        @Override
        public CompletionStage<Done> visit(TransferEvent.RefundDelivered refundSent) {
            // Saga is complete after refunding source
            return CompletableFuture.completedFuture(Done.getInstance());
        }
    }


}
