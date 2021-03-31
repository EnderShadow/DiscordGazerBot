package matt.bot.discord.gazer

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.MessageAction
import net.dv8tion.jda.api.requests.restaction.pagination.ReactionPaginationAction
import net.dv8tion.jda.api.utils.AttachmentOption
import org.apache.commons.collections4.BagUtils
import java.io.File
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer

fun commandLine(bot: JDA)
{
    var selectedTextChannel: TextChannel? = null
    var selectedMember: Member? = null
    
    val scanner = Scanner(System.`in`)
    lineLoop@while(true)
    {
        val line = scanner.nextLine()
        val tokenizer = Tokenizer(line)
        if(!tokenizer.hasNext())
            continue
        
        val command = tokenizer.next().tokenValue
        when(command)
        {
            "shutdown" -> shutdownBot(bot)
            "reload" -> reloadBot(bot)
            "ping" -> println("pong")
            "resetPresence" -> {
                bot.presence.activity = Activity.playing("DM me suggestions or complaints for the Monster Girls discord")
                println("Presence has been reset")
            }
            "react" -> {
                if(tokenizer.hasNext())
                {
                    val channelId = tokenizer.next().tokenValue
                    if(tokenizer.hasNext())
                    {
                        val channel = botGuild.getTextChannelById(channelId)
                        val messageId = tokenizer.next().tokenValue
                        channel?.retrieveMessageById(messageId)?.queue {message ->
                            for(reaction in tokenizer)
                            {
                                val emote = botGuild.getEmotesByName(reaction.tokenValue, false).firstOrNull()
                                if(emote != null)
                                    message.addReaction(emote).queue()
                                else
                                    message.addReaction(reaction.tokenValue).queue()
                            }
                        }
                    }
                }
            }
            "selectedTextChannel" -> println(selectedTextChannel?.name)
            "selectedMember" -> println(selectedMember?.effectiveName)
            "list" -> {
                if(tokenizer.hasNext())
                {
                    when(tokenizer.next().tokenValue)
                    {
                        "textChannels" -> {
                            botGuild.textChannels.map {it.name}.forEach {println(it)}
                        }
                        "members" -> {
                            val users = botGuild.members.toMutableList()
                            if(tokenizer.hasNext())
                            {
                                val filterString = tokenizer.remainingTextAsToken.rawValue
                                users.removeIf {!it.effectiveName.containsSparse(filterString)}
                            }
                            users.forEach {println("${it.user.id}\t${it.effectiveName}")}
                        }
                        else -> println("Unknown command")
                    }
                }
                else
                {
                    println("Not enough command arguments")
                }
            }
            "select" -> {
                if(!tokenizer.hasNext())
                {
                    println("Not enough command arguments")
                    continue@lineLoop
                }
                val category = tokenizer.next().tokenValue
                if(!tokenizer.hasNext())
                {
                    println("Not enough command arguments")
                    continue@lineLoop
                }
                val value = tokenizer.remainingTextAsToken.tokenValue
                when(category)
                {
                    "textChannel" -> {
                        botGuild.textChannels.firstOrNull {it.name == value}?.let {selectedTextChannel = it} ?: println("Cannot find text channel with name \"$value\"")
                    }
                    "member" -> {
                        selectedMember = botGuild.members.firstOrNull {it.user.id == value}
                    }
                }
            }
            "type" -> {
                if(selectedTextChannel == null)
                    println("Select a text channel first")
                else
                    MessageBuilder(tokenizer.remainingTextAsToken.tokenValue).sendTo(selectedTextChannel!!).queue()
            }
            "sudo" -> {
                runCommand(tokenizer.next().tokenValue, tokenizer, CommandLineMessage(botGuild, selectedMember, selectedTextChannel, line))
            }
            "pullMessage" -> {
                if(!tokenizer.hasNext())
                {
                    println("You need to enter a message ID")
                }
                else
                {
                    val id = tokenizer.next().tokenValue
                    val message = getMessage(id)
                    if(message != null)
                    {
                        val messageFile = File("pulledMessages/$id")
                        messageFile.parentFile.mkdirs()
                        messageFile.writeText(message.contentRaw)
                        println("Seccessfully pulled message")
                    }
                    else
                    {
                        println("Cannot find message")
                    }
                }
            }
            "pushMessage" -> {
                if(!tokenizer.hasNext())
                {
                    println("You need to enter a message ID")
                }
                else
                {
                    val id = tokenizer.next().tokenValue
                    val message = getMessage(id)
                    val messageFile = File("pulledMessages/$id")
                    if(message != null && message.author == bot.selfUser && messageFile.exists())
                    {
                        message.editMessage(messageFile.readText()).complete()
                        messageFile.delete()
                        messageFile.parentFile.takeIf {it.list()?.isEmpty() == true}?.delete()
                        println("Successfully pushed message")
                    }
                    else
                    {
                        println("Either the message doesn't exist, you are not the author of this message, or you haven't pulled the message yet")
                    }
                }
            }
        }
    }
}

private fun getMessage(id: String): Message?
{
    return bot.textChannels.asSequence().map {
        try
        {
            it.retrieveMessageById(id).complete()
        }
        catch(e: Exception)
        {
            null
        }
    }.filterNotNull().firstOrNull()
}

private class CommandLineMessage(private val guild: Guild, private val member: Member?, private val textChannel: TextChannel?, private val text: String): Message
{
    override fun getActivity(): MessageActivity? = null
    override fun getJumpUrl() = ""
    override fun isFromType(type: ChannelType) = type == textChannel?.type ?: ChannelType.UNKNOWN
    override fun isEdited() = false
    override fun isPinned() = false
    override fun mentionsEveryone() = false
    override fun getFlags() = EnumSet.noneOf(Message.MessageFlag::class.java)
    override fun getMentionedChannelsBag() = BagUtils.emptyBag<TextChannel>()
    override fun addReaction(emote: Emote): RestAction<Void> = FakeAuditableRestAction()
    override fun addReaction(unicode: String): RestAction<Void> = FakeAuditableRestAction()
    override fun clearReactions(): RestAction<Void> = FakeAuditableRestAction()
    override fun clearReactions(emote: String): RestAction<Void> = FakeAuditableRestAction()
    override fun clearReactions(emote: Emote): RestAction<Void> = FakeAuditableRestAction()
    override fun getReactionById(id: String) = null
    override fun getReactionById(id: Long) = null
    override fun formatTo(p0: Formatter?, p1: Int, p2: Int, p3: Int) {}
    override fun getContentRaw() = text
    override fun getContentStripped() = text
    override fun getGuild() = guild
    override fun isTTS() = false
    override fun isMentioned(mentionable: IMentionable, vararg types: Message.MentionType) = false
    override fun isSuppressedEmbeds() = true
    override fun editMessageFormat(format: String, vararg args: Any?): MessageAction = FakeMessageAction(textChannel)
    override fun getEmotesBag() = BagUtils.emptyBag<Emote>()
    override fun getMentionedChannels() = mutableListOf<TextChannel>()
    override fun getMember() = member ?: guild.owner!!
    override fun getIdLong() = 0L
    override fun getContentDisplay() = text
    override fun getPrivateChannel(): PrivateChannel = throw UnsupportedOperationException()
    override fun getChannelType() = textChannel?.type ?: ChannelType.UNKNOWN
    override fun getAttachments() = mutableListOf<Message.Attachment>()
    override fun getMentionedRoles() = mutableListOf<Role>()
    override fun pin(): RestAction<Void> = FakeAuditableRestAction()
    override fun removeReaction(emote: Emote) = FakeAuditableRestAction<Void>()
    override fun removeReaction(emote: Emote, user: User) = FakeAuditableRestAction<Void>()
    override fun removeReaction(unicode: String) = FakeAuditableRestAction<Void>()
    override fun removeReaction(unicode: String, user: User) = FakeAuditableRestAction<Void>()
    override fun suppressEmbeds(suppressed: Boolean) = FakeAuditableRestAction<Void>()
    override fun getMentionedMembers(guild: Guild) = mutableListOf<Member>()
    override fun getMentionedMembers() = mutableListOf<Member>()
    override fun unpin(): RestAction<Void> = FakeAuditableRestAction()
    override fun getCategory(): Category? = null
    override fun getInvites() = mutableListOf<String>()
    override fun getTimeEdited(): OffsetDateTime = OffsetDateTime.MAX
    override fun getReactionByUnicode(unicode: String) = null
    override fun getMentionedUsers() = mutableListOf<User>()
    override fun getMentionedRolesBag() = BagUtils.emptyBag<Role>()
    override fun getEmotes() = mutableListOf<Emote>()
    override fun getAuthor() = guild.owner!!.user
    override fun getMentionedUsersBag() = BagUtils.emptyBag<User>()
    
    override fun editMessage(newContent: CharSequence): MessageAction = FakeMessageAction(textChannel)
    override fun editMessage(newContent: MessageEmbed): MessageAction = FakeMessageAction(textChannel)
    override fun editMessage(newContent: Message): MessageAction = FakeMessageAction(textChannel)
    override fun delete(): AuditableRestAction<Void> = FakeAuditableRestAction()
    override fun getMentions(vararg types: Message.MentionType?) = mutableListOf<IMentionable>()
    override fun isWebhookMessage() = false
    override fun getEmbeds() = mutableListOf<MessageEmbed>()
    override fun getType() = MessageType.UNKNOWN
    override fun retrieveReactionUsers(emote: Emote): ReactionPaginationAction {
        throw UnsupportedOperationException()
    }
    
    override fun retrieveReactionUsers(unicode: String): ReactionPaginationAction {
        throw UnsupportedOperationException()
    }
    
    override fun getChannel(): MessageChannel = textChannel!!
    override fun getJDA() = bot
    override fun getReactions() = mutableListOf<MessageReaction>()
    override fun getTextChannel(): TextChannel = textChannel!!
    override fun getNonce() = ""
}

private class FakeAuditableRestAction<T>: AuditableRestAction<T>
{
    override fun submit(shouldQueue: Boolean): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        future.completeExceptionally(UnsupportedOperationException())
        return future
    }
    
    override fun complete(shouldQueue: Boolean) = throw UnsupportedOperationException()
    override fun getJDA() = bot
    override fun reason(reason: String?) = this
    
    override fun queue(success: Consumer<in T>?, failure: Consumer<in Throwable>?) {
        failure?.accept(UnsupportedOperationException())
    }
    
    override fun setCheck(checks: BooleanSupplier?) = this
    override fun timeout(time: Long, unit: TimeUnit): AuditableRestAction<T> = this
    override fun deadline(time: Long): AuditableRestAction<T> = this
}

private class FakeMessageAction(private val textChannel: TextChannel?): MessageAction
{
    override fun embed(embed: MessageEmbed?) = this
    
    override fun submit(shouldQueue: Boolean): CompletableFuture<Message> {
        val future = CompletableFuture<Message>()
        future.completeExceptionally(UnsupportedOperationException())
        return future
    }
    
    override fun complete(shouldQueue: Boolean): Message {
        throw UnsupportedOperationException()
    }
    
    override fun content(content: String?) = this
    override fun isEdit() = false
    override fun addFile(data: InputStream, name: String, vararg options: AttachmentOption?) = this
    override fun addFile(file: File, name: String, vararg options: AttachmentOption?) = this
    override fun append(csq: CharSequence?, start: Int, end: Int) = this
    override fun append(c: Char) = this
    override fun tts(isTTS: Boolean) = this
    override fun reset() = this
    override fun setCheck(checks: BooleanSupplier?) = this
    override fun clearFiles() = this
    override fun clearFiles(finalizer: BiConsumer<String, InputStream>) = this
    override fun clearFiles(finalizer: Consumer<InputStream>) = this
    override fun isEmpty() = true
    override fun getChannel() = textChannel ?: throw UnsupportedOperationException()
    override fun getJDA() = bot
    override fun nonce(nonce: String?) = this
    override fun apply(message: Message?) = this
    override fun override(bool: Boolean) = this
    override fun allowedMentions(p0: MutableCollection<Message.MentionType>?) = this
    override fun mention(vararg p0: IMentionable?) = this
    override fun mentionUsers(vararg p0: String?) = this
    override fun mentionRoles(vararg p0: String?) = this
    
    override fun queue(success: Consumer<in Message>?, failure: Consumer<in Throwable>?) {
        failure?.accept(UnsupportedOperationException())
    }
    
    override fun timeout(time: Long, unit: TimeUnit): MessageAction = this
    override fun deadline(time: Long): MessageAction = this
}