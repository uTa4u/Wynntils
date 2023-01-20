/*
 * Copyright © Wynntils 2022.
 * This file is released under AGPLv3. See LICENSE for full license details.
 */
package com.wynntils.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.wynntils.core.commands.CommandBase;
import com.wynntils.core.components.Managers;
import com.wynntils.mc.utils.McUtils;
import com.wynntils.models.quests.QuestInfo;
import com.wynntils.models.quests.type.QuestSortOrder;
import com.wynntils.utils.StringUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

public class QuestCommand extends CommandBase {
    private static final SuggestionProvider<CommandSourceStack> QUEST_SUGGESTION_PROVIDER =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    Managers.Quest.getQuests(QuestSortOrder.ALPHABETIC).stream().map(QuestInfo::getName), builder);

    private static final SuggestionProvider<CommandSourceStack> SORT_SUGGESTION_PROVIDER =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    Arrays.stream(QuestSortOrder.values())
                            .map(order -> order.name().toLowerCase(Locale.ROOT)),
                    builder);

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> getBaseCommandBuilder() {
        return Commands.literal("quest")
                .then(Commands.literal("list")
                        .executes((ctxt) -> listQuests(ctxt, "distance"))
                        .then(Commands.argument("sort", StringArgumentType.word())
                                .suggests(SORT_SUGGESTION_PROVIDER)
                                .executes((ctxt) -> listQuests(ctxt, ctxt.getArgument("sort", String.class)))))
                .then(Commands.literal("search")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(this::searchQuests)))
                .then(Commands.literal("info")
                        .then(Commands.argument("quest", StringArgumentType.greedyString())
                                .suggests(QUEST_SUGGESTION_PROVIDER)
                                .executes(this::questInfo)))
                .then(Commands.literal("track")
                        .then(Commands.argument("quest", StringArgumentType.greedyString())
                                .suggests(QUEST_SUGGESTION_PROVIDER)
                                .executes(this::trackQuest)))
                .then(Commands.literal("untrack").executes(this::untrackQuest))
                .then(Commands.literal("wiki")
                        .then(Commands.argument("quest", StringArgumentType.greedyString())
                                .suggests(QUEST_SUGGESTION_PROVIDER)
                                .executes(this::lookupOnWiki)))
                .executes(this::syntaxError);
    }

    private int listQuests(CommandContext<CommandSourceStack> context, String sort) {
        QuestSortOrder order = QuestSortOrder.fromString(sort);

        Managers.Quest.rescanQuestBook(true, false);

        if (Managers.Quest.getQuestsRaw().isEmpty()) {
            context.getSource()
                    .sendSuccess(
                            Component.literal("Quest Book was not scanned. You might have to retry this command.")
                                    .withStyle(ChatFormatting.YELLOW),
                            false);
        }

        List<QuestInfo> quests = Managers.Quest.getQuests(order).stream()
                .filter(QuestInfo::isTrackable)
                .toList();

        if (quests.isEmpty()) {
            context.getSource()
                    .sendFailure(Component.literal("No active quests found!").withStyle(ChatFormatting.RED));
            return 1;
        }

        MutableComponent response = Component.literal("Active quests:").withStyle(ChatFormatting.AQUA);
        generateQuestList(quests, response);

        context.getSource().sendSuccess(response, false);
        return 1;
    }

    private int searchQuests(CommandContext<CommandSourceStack> context) {
        String searchText = context.getArgument("text", String.class);

        Managers.Quest.rescanQuestBook(true, false);

        if (Managers.Quest.getQuestsRaw().isEmpty()) {
            context.getSource()
                    .sendSuccess(
                            Component.literal("Quest Book was not scanned. You might have to retry this command.")
                                    .withStyle(ChatFormatting.YELLOW),
                            false);
        }

        List<QuestInfo> quests;
        MutableComponent response;

        quests = Managers.Quest.getQuestsRaw().stream()
                .filter(quest -> StringUtils.initialMatch(quest.getName(), searchText)
                        || StringUtils.initialMatch(quest.getNextTask(), searchText))
                .toList();

        if (quests.isEmpty()) {
            context.getSource()
                    .sendFailure(Component.literal("No matching quests found!").withStyle(ChatFormatting.RED));
            return 1;
        }

        response = Component.literal("Matching quests:").withStyle(ChatFormatting.AQUA);

        generateQuestList(quests, response);

        context.getSource().sendSuccess(response, false);
        return 1;
    }

    private void generateQuestList(List<QuestInfo> quests, MutableComponent response) {
        Vec3 playerLocation = McUtils.player().position();

        for (QuestInfo quest : quests) {
            double distance = quest.getNextLocation().isEmpty()
                    ? 0
                    : playerLocation.distanceTo(quest.getNextLocation().get().toVec3());

            response.append(Component.literal("\n - ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(quest.getName())
                            .withStyle(style -> style.withClickEvent(
                                    new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest info " + quest.getName())))
                            .withStyle(style -> style.withHoverEvent(
                                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click for info"))))
                            .withStyle(ChatFormatting.WHITE));
            if (distance > 0) {
                response.append(Component.literal(" (" + (int) Math.round(distance) + " m)")
                        .withStyle(ChatFormatting.DARK_GREEN));
            }

            if (quest.equals(Managers.Quest.getTrackedQuest())) {
                response.append(Component.literal(" [Tracked]")
                        .withStyle(ChatFormatting.DARK_AQUA)
                        .withStyle(style ->
                                style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest untrack")))
                        .withStyle(style -> style.withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT, Component.literal("Click to stop tracking")))));
            }
        }
    }

    private int questInfo(CommandContext<CommandSourceStack> context) {
        String questName = context.getArgument("quest", String.class);
        QuestInfo quest = getQuestInfo(context, questName);
        if (quest == null) return 0;

        MutableComponent response =
                Component.literal("Info for quest: " + quest.getName()).withStyle(ChatFormatting.AQUA);
        response.append(Component.literal("\n - Status: ").withStyle(ChatFormatting.WHITE))
                .append(quest.getStatus().getQuestBookComponent())
                .append(Component.literal("\n - Level: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(Integer.toString(quest.getLevel())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\n - Length: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(
                                StringUtils.capitalized(quest.getLength().toString()))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\n - Next task: ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(quest.getNextTask()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.literal("[Track quest]")
                        .withStyle(style -> style.withClickEvent(new ClickEvent(
                                        ClickEvent.Action.RUN_COMMAND, "/quest track " + quest.getName()))
                                .withHoverEvent(new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT, Component.literal("Click to track quest"))))
                        .withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" "))
                .append(Component.literal("[Lookup on wiki]")
                        .withStyle(style -> style.withClickEvent(
                                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/quest wiki " + quest.getName()))
                                .withHoverEvent(new HoverEvent(
                                        HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to lookup quest on wiki"))))
                        .withStyle(ChatFormatting.DARK_AQUA));

        context.getSource().sendSuccess(response, false);
        return 1;
    }

    private int trackQuest(CommandContext<CommandSourceStack> context) {
        String questName = context.getArgument("quest", String.class);
        QuestInfo quest = getQuestInfo(context, questName);
        if (quest == null) return 0;

        Managers.Quest.startTracking(quest);
        MutableComponent response =
                Component.literal("Now tracking quest " + quest.getName()).withStyle(ChatFormatting.AQUA);
        context.getSource().sendSuccess(response, false);
        return 1;
    }

    private int untrackQuest(CommandContext<CommandSourceStack> context) {
        QuestInfo trackedQuest = Managers.Quest.getTrackedQuest();
        if (trackedQuest == null) {
            context.getSource()
                    .sendFailure(Component.literal("No quest currently tracked").withStyle(ChatFormatting.RED));
            return 0;
        }

        Managers.Quest.stopTracking();

        MutableComponent response = Component.literal("Stopped tracking quest " + trackedQuest.getName())
                .withStyle(ChatFormatting.AQUA);
        context.getSource().sendSuccess(response, false);
        return 1;
    }

    private int lookupOnWiki(CommandContext<CommandSourceStack> context) {
        String questName = context.getArgument("quest", String.class);
        QuestInfo quest = getQuestInfo(context, questName);
        if (quest == null) return 0;

        Managers.Quest.openQuestOnWiki(quest);
        MutableComponent response =
                Component.literal("Quest opened on wiki " + quest.getName()).withStyle(ChatFormatting.AQUA);
        context.getSource().sendSuccess(response, false);
        return 1;
    }

    private int syntaxError(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.literal("Missing argument").withStyle(ChatFormatting.RED));
        return 0;
    }

    private QuestInfo getQuestInfo(CommandContext<CommandSourceStack> context, String questName) {
        String questNameLowerCase = questName.toLowerCase(Locale.ROOT);
        List<QuestInfo> matchingQuests = Managers.Quest.getQuestsRaw().stream()
                .filter(questInfo ->
                        questInfo.getName().toLowerCase(Locale.ROOT).contains(questNameLowerCase))
                .toList();

        if (matchingQuests.size() < 1) {
            context.getSource()
                    .sendFailure(Component.literal("Quest '" + questName + "' not found")
                            .withStyle(ChatFormatting.RED));
            return null;
        } else if (matchingQuests.size() > 1) {
            MutableComponent error = Component.literal("Quest '" + questName + "' match several quests:")
                    .withStyle(ChatFormatting.RED);
            for (var quest : matchingQuests) {
                error.append(Component.literal("\n - ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(quest.getName()).withStyle(ChatFormatting.WHITE));
            }
            context.getSource().sendFailure(error);
            return null;
        }

        return matchingQuests.get(0);
    }
}
